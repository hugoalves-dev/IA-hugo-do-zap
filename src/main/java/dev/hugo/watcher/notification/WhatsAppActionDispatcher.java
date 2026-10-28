package dev.hugo.watcher.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Sends isolated action records once; ambiguous outcomes stay unresolved and are never retried. */
@Service
public class WhatsAppActionDispatcher {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WhatsAppActionService actions;
    private final EvolutionActionTransport transport;
    private final Clock clock;
    private final boolean realSendEnabled;

    public WhatsAppActionDispatcher(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                    WhatsAppActionService actions, EvolutionActionTransport transport,
                                    Clock clock, @Value("${watcher.whatsapp-actions.real-send-enabled:false}") boolean realSendEnabled) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.actions = actions;
        this.transport = transport;
        this.clock = clock;
        this.realSendEnabled = realSendEnabled;
    }

    public void dispatch(UUID actionId) {
        if (actionId == null || !realSendEnabled) return;
        boolean claimed = Boolean.TRUE.equals(transactions.execute(status -> jdbc.update("""
                update whatsapp_action_outbox set status='DISPATCHED',updated_at=?
                 where action_id=? and status='PENDING' and expires_at>?
                """, Timestamp.from(clock.instant()), actionId, Timestamp.from(clock.instant())) == 1));
        if (!claimed) return;

        WhatsAppActionService.AuthorizedAction latest = actions.revalidate(actionId, clock.instant());
        if (latest == null) {
            updateStatus(actionId, "SUPPRESSED", "AuthorizationRevoked");
            return;
        }

        EvolutionSendResult result;
        try {
            result = transport.send(latest.chatJid(), latest.body(), latest.actionId().toString());
            if (result == null) result = EvolutionSendResult.unknown();
        } catch (RuntimeException timeoutOrTransportFailure) {
            result = EvolutionSendResult.unknown();
        }
        EvolutionSendResult sendResult = result;
        switch (sendResult.outcome()) {
            case DELIVERED -> transactions.executeWithoutResult(status -> {
                int updated = jdbc.update("""
                        update whatsapp_action_outbox set status='DELIVERED',updated_at=?
                         where action_id=? and status='DISPATCHED'
                        """, Timestamp.from(clock.instant()), actionId);
                if (updated == 1) {
                    jdbc.update("""
                            insert into decision_cooldowns(chat_jid,participant_jid,last_real_action_at,last_real_decision_id)
                            select chat_jid,'',?,decision_id from whatsapp_action_outbox where action_id=?
                            on conflict(chat_jid,participant_jid) do update
                            set last_real_action_at=excluded.last_real_action_at,last_real_decision_id=excluded.last_real_decision_id
                            where decision_cooldowns.last_real_action_at is null
                               or decision_cooldowns.last_real_action_at<=excluded.last_real_action_at
                            """, Timestamp.from(clock.instant()), actionId);
                }
            });
            case FAILED -> updateStatus(actionId, "FAILED", "ConfirmedFailure");
            case UNKNOWN -> updateStatus(actionId, "UNKNOWN", "AmbiguousOutcome");
        }
    }

    public void dispatchNext() {
        purgeExpired(clock.instant());
        if (!realSendEnabled) return;
        List<UUID> pending = jdbc.query("select action_id from whatsapp_action_outbox where status='PENDING' and expires_at>? order by created_at limit 1",
                (rs, row) -> rs.getObject(1, UUID.class), Timestamp.from(clock.instant()));
        if (!pending.isEmpty()) dispatch(pending.getFirst());
    }

    /** Purges every expired state, including UNKNOWN, without changing the non-identifying decision tombstone. */
    public int purgeExpired(java.time.Instant now) {
        return transactions.execute(status -> jdbc.update("""
                delete from whatsapp_action_outbox where action_id in (
                    select action_id from whatsapp_action_outbox where expires_at<=?
                     order by expires_at limit 1000 for update skip locked
                )
                """, Timestamp.from(now)));
    }

    private void updateStatus(UUID id, String state, String error) {
        jdbc.update("update whatsapp_action_outbox set status=?,last_error=?,updated_at=? where action_id=? and status='DISPATCHED'",
                state, error, Timestamp.from(clock.instant()), id);
    }
}
