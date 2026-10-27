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
                 where action_id=? and status='PENDING'
                """, Timestamp.from(clock.instant()), actionId) == 1));
        if (!claimed) return;

        WhatsAppActionService.AuthorizedAction latest = actions.revalidate(actionId, clock.instant());
        if (latest == null) {
            updateStatus(actionId, "SUPPRESSED", null, "AuthorizationRevoked");
            return;
        }

        EvolutionSendResult result;
        try {
            result = transport.send(latest.chatJid(), latest.body(), latest.actionId().toString());
            if (result == null) result = EvolutionSendResult.uncertain();
        } catch (RuntimeException timeoutOrTransportFailure) {
            result = EvolutionSendResult.uncertain();
        }
        EvolutionSendResult sendResult = result;
        switch (sendResult.outcome()) {
            case DELIVERED -> transactions.executeWithoutResult(status -> {
                int updated = jdbc.update("""
                        update whatsapp_action_outbox set status='DELIVERED',provider_message_id=?,updated_at=?
                         where action_id=? and status='DISPATCHED'
                        """, sendResult.providerMessageId(), Timestamp.from(clock.instant()), actionId);
                if (updated == 1) {
                    jdbc.update("""
                            insert into decision_cooldowns(chat_jid,participant_jid,last_real_action_at)
                            select chat_jid,'',? from whatsapp_action_outbox where action_id=?
                            on conflict(chat_jid,participant_jid) do update set last_real_action_at=excluded.last_real_action_at
                            """, Timestamp.from(clock.instant()), actionId);
                }
            });
            case FAILED -> updateStatus(actionId, "FAILED", null, "ConfirmedFailure");
            case UNCERTAIN -> updateStatus(actionId, "UNCERTAIN", sendResult.providerMessageId(), "AmbiguousOutcome");
        }
    }

    public void dispatchNext() {
        if (!realSendEnabled) return;
        List<UUID> pending = jdbc.query("select action_id from whatsapp_action_outbox where status='PENDING' order by created_at limit 1",
                (rs, row) -> rs.getObject(1, UUID.class));
        if (!pending.isEmpty()) dispatch(pending.getFirst());
    }

    /** Reconcile only with explicit provider evidence; never infer delivery from a timeout. */
    public boolean reconcileDelivered(UUID actionId, String providerMessageId) {
        if (actionId == null || providerMessageId == null || providerMessageId.isBlank()) return false;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            List<String> rows = jdbc.query("""
                    update whatsapp_action_outbox set status='DELIVERED',provider_message_id=?,updated_at=?
                     where action_id=? and status in ('UNCERTAIN','DISPATCHED') returning chat_jid,participant_jid
                    """, (rs, row) -> rs.getString("chat_jid") + "\n" + rs.getString("participant_jid"),
                    providerMessageId, Timestamp.from(clock.instant()), actionId);
            if (rows.isEmpty()) return false;
            String[] scope = rows.getFirst().split("\n", 2);
            jdbc.update("""
                    insert into decision_cooldowns(chat_jid,participant_jid,last_real_action_at) values (?,'',?)
                    on conflict(chat_jid,participant_jid) do update set last_real_action_at=excluded.last_real_action_at
                    """, scope[0], Timestamp.from(clock.instant()));
            return true;
        }));
    }

    /** Close an ambiguous send only when external evidence confirms it was not delivered. */
    public boolean reconcileNotDelivered(UUID actionId) {
        if (actionId == null) return false;
        return jdbc.update("update whatsapp_action_outbox set status='FAILED',last_error='ReconciledNotDelivered',updated_at=? where action_id=? and status in ('UNCERTAIN','DISPATCHED')",
                Timestamp.from(clock.instant()), actionId) == 1;
    }

    private void updateStatus(UUID id, String state, String providerId, String error) {
        jdbc.update("update whatsapp_action_outbox set status=?,provider_message_id=?,last_error=?,updated_at=? where action_id=? and status='DISPATCHED'",
                state, providerId, error, Timestamp.from(clock.instant()), id);
    }
}
