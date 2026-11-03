package dev.hugo.watcher.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import dev.hugo.watcher.BotReplyFormatter;
import dev.hugo.watcher.notification.WhatsAppActionPersistenceRepository.Queued;
import dev.hugo.watcher.notification.WhatsAppActionPersistenceRepository.Action;
import dev.hugo.watcher.notification.WhatsAppActionPersistenceRepository.Authorization;

/** Queues only actions backed by a persisted decision and current explicit authorization. */
@Service
public class WhatsAppActionService {
    private final WhatsAppActionPersistenceRepository persistence;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean realSendEnabled;
    private DecisionRuntimeGate runtimeGate;

    /** Production constructor: authorization and transaction boundaries stay in the service. */
    public WhatsAppActionService(WhatsAppActionPersistenceRepository persistence,
                                 PlatformTransactionManager manager, Clock clock, boolean realSendEnabled) {
        this.persistence = persistence;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.realSendEnabled = realSendEnabled;
    }

    /** Compatibility constructor retained for standalone Testcontainers tests. */
    public WhatsAppActionService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                                 boolean realSendEnabled, String ownerJid) {
        this(new WhatsAppActionPersistenceRepository(jdbc), manager, clock, realSendEnabled);
        this.runtimeGate = new DecisionRuntimeGate(jdbc, clock, new dev.hugo.watcher.WatcherProperties(), ownerJid);
    }

    public WhatsAppActionService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, boolean enabled) {
        this(jdbc,manager,clock,enabled,"");
    }
    @Autowired public void configureRuntimeGate(DecisionRuntimeGate gate) { this.runtimeGate=gate; }

    public ActionResult enqueue(UUID persistedDecisionId) {
        if (!realSendEnabled) return ActionResult.rejected("REAL_ACTION_DISABLED");
        if (persistedDecisionId == null) return ActionResult.rejected("DECISION_NOT_FOUND");
        return transactions.execute(status -> {
            persistence.lockDecision(persistedDecisionId);
            boolean decisionExists = persistence.decisionExists(persistedDecisionId);
            if (!decisionExists) return ActionResult.rejected("DECISION_NOT_FOUND");
            List<Queued> prior = persistence.queuedForDecision(persistedDecisionId);
            if (!prior.isEmpty()) {
                Queued queued = prior.getFirst();
                boolean active = queued.status().equals("PENDING") || queued.status().equals("DISPATCHED") || queued.status().equals("UNKNOWN");
                return active && queued.expiresAt().isAfter(clock.instant())
                        ? ActionResult.accepted(queued.id()) : ActionResult.rejected("DECISION_ALREADY_PROCESSED");
            }
            boolean alreadyEnqueued = persistence.alreadyEnqueued(persistedDecisionId);
            if (alreadyEnqueued) return ActionResult.rejected("DECISION_ALREADY_PROCESSED");

            Authorization authorization = currentAuthorization(persistedDecisionId, clock.instant());
            if (authorization == null) return ActionResult.rejected("AUTHORIZATION_OR_POLICY_BLOCKED");
            persistence.lockChat(authorization.chatJid());
            authorization = currentAuthorization(persistedDecisionId, clock.instant());
            if (authorization == null) return ActionResult.rejected("AUTHORIZATION_OR_POLICY_BLOCKED");
            int active = persistence.activeActionsForChat(authorization.chatJid());
            if (active > 0) return ActionResult.rejected("ACTION_ALREADY_IN_FLIGHT");
            UUID actionId = UUID.randomUUID();
            Instant createdAt = clock.instant();
            persistence.insertAction(actionId, persistedDecisionId, authorization, createdAt);
            persistence.markEnqueued(persistedDecisionId);
            return ActionResult.accepted(actionId);
        });
    }

    /** Reads all authorization inputs again immediately before any Evolution request. */
    public AuthorizedAction revalidate(UUID actionId, Instant now) {
        if (actionId == null || now == null || !realSendEnabled) return null;
        List<Action> actions = persistence.dispatchedAction(actionId, now);
        if (actions.isEmpty()) return null;
        Action expected = actions.getFirst();
        Authorization latest = currentAuthorization(expected.decisionId(), now);
        if (latest == null || !latest.chatJid().equals(expected.chatJid())
                || !latest.participantJid().equals(expected.participantJid())
                || !latest.situation().equals(expected.situation())
                || !latest.templateId().equals(expected.templateId())
                || latest.templateVersion() != expected.templateVersion()) return null;
        String deliveryBody = BotReplyFormatter.whatsAppTemplate(latest.body());
        if (deliveryBody == null) return null;
        return new AuthorizedAction(actionId, expected.decisionId(), expected.chatJid(), deliveryBody);
    }

    private Authorization currentAuthorization(UUID decisionId, Instant now) {
        if (!runtimeGate.allows(decisionId,false)) return null;
        List<Authorization> rows = persistence.authorizations(decisionId);
        if (rows.isEmpty()) return null;
        Authorization a = rows.getFirst();
        if (!a.realActionAllowed() || !"WOULD_ACT".equals(a.decisionState())
                || !a.templateEnabled() || !a.identifiesAutomation()) return null;
        if (!a.wouldReply()) return null;
        if (BotReplyFormatter.whatsAppTemplate(a.body()) == null) return null;
        int optedIn = persistence.authorizationCount(a);
        if (optedIn != 1) return null;
        return a;
    }

    public record AuthorizedAction(UUID actionId, UUID decisionId, String chatJid, String body) { }

}
