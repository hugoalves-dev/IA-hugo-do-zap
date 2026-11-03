package dev.hugo.watcher.notification;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.OutboxAction;
import dev.hugo.watcher.notification.IntelligentAlertPersistenceRepository.AlertContext;
import dev.hugo.watcher.notification.IntelligentAlertPersistenceRepository.SourceMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Enqueues only persisted, high-confidence urgency alerts with an objective source reference. */
@Service
public class IntelligentAlertService {
    public static final String ACTION_TYPE = "intelligent_alert";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final IntelligentAlertPersistenceRepository persistence;
    private final TransactionTemplate transactions;
    private final NotificationOutboxRepository outbox;
    private final Clock clock;
    private final boolean enabled;
    private final String ownerJid;
    private DecisionRuntimeGate runtimeGate;

    /** Production constructor, wired explicitly without selecting the legacy JDBC adapter. */
    public IntelligentAlertService(IntelligentAlertPersistenceRepository persistence,
                                   PlatformTransactionManager manager,
                                   NotificationOutboxRepository outbox, Clock clock,
                                   boolean enabled, String ownerJid) {
        this.persistence = persistence;
        this.transactions = new TransactionTemplate(manager);
        this.outbox = outbox;
        this.clock = clock;
        this.enabled = enabled;
        this.ownerJid = ownerJid == null ? "" : ownerJid.strip();
    }

    /** Legacy constructor retained for existing standalone tests during #24. */
    public IntelligentAlertService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                   NotificationOutboxRepository outbox, Clock clock,
                                   boolean enabled, String ownerJid) {
        this(new IntelligentAlertPersistenceRepository(jdbc), manager, outbox, clock, enabled, ownerJid);
        this.runtimeGate = new DecisionRuntimeGate(jdbc, clock, new dev.hugo.watcher.WatcherProperties(), this.ownerJid);
    }

    public IntelligentAlertService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                   NotificationOutboxRepository outbox, Clock clock, boolean enabled) {
        this(jdbc, manager, outbox, clock, enabled, "");
    }

    @Autowired public void configureRuntimeGate(DecisionRuntimeGate gate) { this.runtimeGate=gate; }

    public AlertEnqueueResult enqueue(UUID persistedDecisionId) {
        if (!enabled) return AlertEnqueueResult.rejected("ALERTS_DISABLED");
        if (persistedDecisionId == null) return AlertEnqueueResult.rejected("DECISION_NOT_FOUND");
        return transactions.execute(status -> {
            persistence.lockDecision(persistedDecisionId);
            String key = ACTION_TYPE + ":" + persistedDecisionId;
            List<OutboxAction> prior = findOutbox(key);
            if (!prior.isEmpty()) return AlertEnqueueResult.accepted(prior.getFirst());
            boolean alreadyEnqueued = persistence.alreadyEnqueued(persistedDecisionId);
            if (alreadyEnqueued) return AlertEnqueueResult.rejected("DECISION_ALREADY_PROCESSED");
            AlertContext context = eligible(persistedDecisionId);
            if (context == null) return AlertEnqueueResult.rejected("ALERT_POLICY_BLOCKED");
            Instant createdAt = clock.instant();
            String payload = JSON.writeValueAsString(Map.of("eventType", ACTION_TYPE,
                    "decisionId", persistedDecisionId.toString(), "urgency", context.urgency(),
                    "reference", persistedDecisionId.toString()));
            persistence.markEnqueued(persistedDecisionId);
            outbox.enqueue(key, ACTION_TYPE, payload, "[]", createdAt, createdAt.plusSeconds(86_400));
            List<OutboxAction> created = findOutbox(key);
            if (created.isEmpty()) throw new IllegalStateException("intelligent alert was not persisted");
            return AlertEnqueueResult.accepted(created.getFirst());
        });
    }

    /** Rechecks audit, source evidence and every current authorization before Telegram delivery. */
    public boolean revalidate(OutboxAction action, Instant now) {
        if (!enabled || action == null || now == null || !ACTION_TYPE.equals(action.actionType())
                || !action.sensitiveExpiresAt().isAfter(now)) return false;
        UUID decisionId;
        try {
            var payload = JSON.readTree(action.payload());
            decisionId = UUID.fromString(payload.path("decisionId").asString(""));
            if (!action.actionKey().equals(ACTION_TYPE + ":" + decisionId)
                    || !ACTION_TYPE.equals(payload.path("eventType").asString(""))
                    || !decisionId.toString().equals(payload.path("reference").asString(""))) return false;
        } catch (RuntimeException invalidPayload) { return false; }
        int pending = persistence.processingCount(action);
        if (pending != 1) return false;
        return eligible(decisionId) != null;
    }

    private AlertContext eligible(UUID decisionId) {
        if (runtimeGate == null || !runtimeGate.allows(decisionId,true)) return null;
        List<AlertContext> contexts = persistence.eligibleContexts(decisionId);
        if (contexts.isEmpty()) return null;
        AlertContext c = contexts.getFirst();
        if (!"WOULD_ACT".equals(c.decisionState()) || !c.realActionAllowed() || !c.wouldAlert()
                || !"CLASSIFIED".equals(c.classificationStatus())
                || !("HIGH".equals(c.urgency()) || "CRITICAL".equals(c.urgency()))
                || c.confidence() < 0.65 || c.referenceHash() == null || c.referenceHash().isBlank()
                || c.enqueued() && !isOutboxAlreadyProcessing(decisionId)
                || !c.templateEnabled() || !c.identifiesAutomation()
                || !c.templateBody().stripLeading().toLowerCase(java.util.Locale.ROOT).startsWith("resposta automática:")) return null;
        if (!c.referenceHash().matches("[0-9a-fA-F]{64}")) return null;
        List<SourceMessage> sources = persistence.sourceMessages(decisionId);
        for (SourceMessage source : sources) {
            if (hash(source.externalId()).equalsIgnoreCase(c.referenceHash()) && isPersonalReference(source)) return c;
        }
        return null;
    }

    private boolean isPersonalReference(SourceMessage source) {
        if (ownerJid.isBlank()) return false;
        try {
            JsonNode payload = JSON.readTree(source.payload());
            if (!payload.path("fromMe").isBoolean() || payload.path("fromMe").booleanValue()) return false;
            JsonNode content = payload.path("content");
            for (String variant : List.of("extendedTextMessage", "imageMessage", "videoMessage", "documentMessage")) {
                JsonNode context = content.path(variant).path("contextInfo");
                JsonNode mentioned = context.path("mentionedJid");
                if (mentioned.isArray()) for (JsonNode jid : mentioned) {
                    if (jid.isString() && ownerJid.equals(jid.stringValue())) return true;
                }
                String stanzaId = context.path("stanzaId").stringValueOpt().orElse("");
                if (!stanzaId.isBlank()) {
                    int references = persistence.referenceCount(source.chatJid(), stanzaId, clock.instant());
                    if (references == 1) return true;
                }
            }
        } catch (RuntimeException invalidEvidence) { return false; }
        return false;
    }

    private boolean isOutboxAlreadyProcessing(UUID decisionId) {
        return persistence.isOutboxAlreadyProcessing(decisionId);
    }

    private List<OutboxAction> findOutbox(String key) {
        return persistence.findOutbox(key);
    }

    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

}
