package dev.hugo.watcher.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Sends isolated action records once; ambiguous outcomes stay unresolved and are never retried. */
@Service
public class WhatsAppActionDispatcher {
    private final WhatsAppActionDispatchRepository persistence;
    private final TransactionTemplate transactions;
    private final WhatsAppActionService actions;
    private final EvolutionActionTransport transport;
    private final Clock clock;
    private final boolean realSendEnabled;
    private final DecisionDeliveryCoordinator deliveries;

    /** Production dependencies share the same managed transaction/dispatch admission model. */
    public WhatsAppActionDispatcher(WhatsAppActionDispatchRepository persistence, PlatformTransactionManager manager,
                                    WhatsAppActionService actions, EvolutionActionTransport transport,
                                    Clock clock, boolean realSendEnabled, DecisionDeliveryCoordinator deliveries) {
        this.persistence = persistence;
        this.deliveries = deliveries;
        this.transactions = new TransactionTemplate(manager);
        this.actions = actions;
        this.transport = transport;
        this.clock = clock;
        this.realSendEnabled = realSendEnabled;
    }

    /** Compatibility constructor for existing PostgreSQL integration tests. */
    public WhatsAppActionDispatcher(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                    WhatsAppActionService actions, EvolutionActionTransport transport,
                                    Clock clock, boolean realSendEnabled) {
        this(new WhatsAppActionDispatchRepository(jdbc), manager, actions, transport, clock,
                realSendEnabled, new DecisionDeliveryCoordinator(jdbc, manager));
    }

    public void dispatch(UUID actionId) {
        if (actionId == null || !realSendEnabled) return;
        boolean claimed = Boolean.TRUE.equals(transactions.execute(
                status -> persistence.claimPending(actionId, clock.instant()) == 1));
        if (!claimed) return;

        WhatsAppActionService.AuthorizedAction latest = actions.revalidate(actionId, clock.instant());
        if (latest == null) {
            updateStatus(actionId, "SUPPRESSED", "AuthorizationRevoked");
            return;
        }

        if (!deliveries.begin(latest.decisionId(),"WHATSAPP",actionId,clock.instant())) {
            updateStatus(actionId,"PENDING","ConversationDispatchUnresolved"); return;
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
                int updated = persistence.markDelivered(actionId, clock.instant());
                if (updated == 1) {
                    deliveries.finish(latest.decisionId(),"WHATSAPP",actionId,"CONFIRMED",clock.instant());
                    persistence.advanceCooldown(actionId, clock.instant());
                }
            });
            case FAILED -> updateStatus(actionId, "FAILED", "ConfirmedFailure");
            case UNKNOWN -> updateStatus(actionId, "UNKNOWN", "AmbiguousOutcome");
        }
    }

    public void dispatchNext() {
        purgeExpired(clock.instant());
        if (!realSendEnabled) return;
        List<UUID> pending = persistence.nextPending(clock.instant());
        if (!pending.isEmpty()) dispatch(pending.getFirst());
    }

    /** Purges every expired state, including UNKNOWN, without changing the non-identifying decision tombstone. */
    public int purgeExpired(java.time.Instant now) {
        return transactions.execute(status -> persistence.purgeExpired(now));
    }

    private void updateStatus(UUID id, String state, String error) {
        transactions.executeWithoutResult(status -> {
            int updated = persistence.updateStatus(id, state, error, clock.instant());
            if(updated==1 && (state.equals("UNKNOWN") || state.equals("FAILED"))) {
                UUID decisionId = persistence.decisionId(id);
                deliveries.finish(decisionId,"WHATSAPP",id,state.equals("UNKNOWN")?"UNKNOWN":"RETRYABLE",clock.instant());
            }
        });
    }
}
