package dev.hugo.watcher.notification;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.OutboxAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.sql.Timestamp;
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
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NotificationOutboxRepository outbox;
    private final Clock clock;
    private final boolean enabled;
    private final String ownerJid;

    @Autowired
    public IntelligentAlertService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                   NotificationOutboxRepository outbox, Clock clock,
                                   @Value("${watcher.telegram.intelligent-alerts-enabled:false}") boolean enabled,
                                   @Value("${watcher.whatsapp-actions.owner-jid:}") String ownerJid) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.outbox = outbox;
        this.clock = clock;
        this.enabled = enabled;
        this.ownerJid = ownerJid == null ? "" : ownerJid.strip();
    }

    public IntelligentAlertService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                   NotificationOutboxRepository outbox, Clock clock, boolean enabled) {
        this(jdbc, manager, outbox, clock, enabled, "");
    }

    public AlertEnqueueResult enqueue(UUID persistedDecisionId) {
        if (!enabled) return AlertEnqueueResult.rejected("ALERTS_DISABLED");
        if (persistedDecisionId == null) return AlertEnqueueResult.rejected("DECISION_NOT_FOUND");
        return transactions.execute(status -> {
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 947223))", rs -> { }, persistedDecisionId.toString());
            String key = ACTION_TYPE + ":" + persistedDecisionId;
            List<OutboxAction> prior = findOutbox(key);
            if (!prior.isEmpty()) return AlertEnqueueResult.accepted(prior.getFirst());
            Boolean alreadyEnqueued = jdbc.query("select intelligent_alert_enqueued from simulation_decisions where batch_id=?",
                    rs -> rs.next() && rs.getBoolean(1), persistedDecisionId);
            if (Boolean.TRUE.equals(alreadyEnqueued)) return AlertEnqueueResult.rejected("DECISION_ALREADY_PROCESSED");
            AlertContext context = eligible(persistedDecisionId);
            if (context == null) return AlertEnqueueResult.rejected("ALERT_POLICY_BLOCKED");
            Instant createdAt = clock.instant();
            String payload = JSON.writeValueAsString(Map.of("eventType", ACTION_TYPE,
                    "decisionId", persistedDecisionId.toString(), "urgency", context.urgency(),
                    "reference", persistedDecisionId.toString()));
            jdbc.update("update simulation_decisions set intelligent_alert_enqueued=true where batch_id=?", persistedDecisionId);
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
        Integer pending = jdbc.queryForObject("select count(*) from outbox_actions where id=? and action_key=? and action_type=? and status='PROCESSING'",
                Integer.class, action.id(), action.actionKey(), ACTION_TYPE);
        if (pending == null || pending != 1) return false;
        return eligible(decisionId) != null;
    }

    private AlertContext eligible(UUID decisionId) {
        List<AlertContext> contexts = jdbc.query("""
                select b.chat_jid,d.decision_state,d.real_action_allowed,d.would_alert,
                       d.classification_status,d.classification_urgency,d.classification_confidence,
                       d.intelligent_alert_reference_hash,d.intelligent_alert_enqueued,
                       s.ai_classification_enabled,s.intelligent_alerts_enabled,s.kill_switch_enabled,
                       t.template_id,t.version,t.enabled,t.identifies_automation,t.body
                  from simulation_decisions d
                  join simulation_batches b on b.batch_id=d.batch_id
                  cross join automation_settings s
                  join chat_automation_authorizations a on a.chat_jid=b.chat_jid
                    and a.situation='CRITICAL_URGENCY'
                  join response_templates t on t.template_id=a.template_id and t.version=a.template_version
                    and t.situation=a.situation
                 where d.batch_id=? and s.singleton_id=1
                 order by t.template_id,t.version desc limit 1
                """, (rs, row) -> new AlertContext(rs.getString("chat_jid"), rs.getString("decision_state"),
                rs.getBoolean("real_action_allowed"), rs.getBoolean("would_alert"),
                rs.getString("classification_status"), rs.getString("classification_urgency"),
                rs.getDouble("classification_confidence"), rs.getString("intelligent_alert_reference_hash"),
                rs.getBoolean("intelligent_alert_enqueued"), rs.getBoolean("ai_classification_enabled"),
                rs.getBoolean("intelligent_alerts_enabled"), rs.getBoolean("kill_switch_enabled"),
                rs.getString("template_id"), rs.getInt("version"), rs.getBoolean("enabled"),
                rs.getBoolean("identifies_automation"), rs.getString("body")), decisionId);
        if (contexts.isEmpty()) return null;
        AlertContext c = contexts.getFirst();
        if (!"WOULD_ACT".equals(c.decisionState()) || !c.realActionAllowed() || !c.wouldAlert()
                || !"CLASSIFIED".equals(c.classificationStatus())
                || !("HIGH".equals(c.urgency()) || "CRITICAL".equals(c.urgency()))
                || c.confidence() < 0.65 || c.referenceHash() == null || c.referenceHash().isBlank()
                || c.enqueued() && !isOutboxAlreadyProcessing(decisionId)
                || !c.aiEnabled() || !c.alertsEnabled() || c.killSwitchEnabled()
                || !c.templateEnabled() || !c.identifiesAutomation()
                || !c.templateBody().stripLeading().toLowerCase(java.util.Locale.ROOT).startsWith("resposta automática:")) return null;
        if (!c.referenceHash().matches("[0-9a-fA-F]{64}")) return null;
        List<SourceMessage> sources = jdbc.query("select external_id,chat_jid,payload::text from simulation_batch_messages where batch_id=?",
                (rs, row) -> new SourceMessage(rs.getString("external_id"), rs.getString("chat_jid"), rs.getString("payload")), decisionId);
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
                    Integer references = jdbc.queryForObject("""
                            select count(*) from whatsapp_owner_message_references
                             where chat_jid=? and external_id=? and expires_at > ?
                            """, Integer.class, source.chatJid(), stanzaId, Timestamp.from(clock.instant()));
                    if (references != null && references == 1) return true;
                }
            }
        } catch (RuntimeException invalidEvidence) { return false; }
        return false;
    }

    private boolean isOutboxAlreadyProcessing(UUID decisionId) {
        Integer count = jdbc.queryForObject("select count(*) from outbox_actions where action_key=? and status='PROCESSING'",
                Integer.class, ACTION_TYPE + ":" + decisionId);
        return count != null && count > 0;
    }

    private List<OutboxAction> findOutbox(String key) {
        return jdbc.query("""
                select id,action_key,action_type,payload::text,pending_snapshot::text,attempts,created_at,sensitive_expires_at
                  from outbox_actions where action_key=?
                """, (rs, row) -> new OutboxAction(rs.getLong("id"), rs.getString("action_key"), rs.getString("action_type"),
                rs.getString("payload"), rs.getString("pending_snapshot"), rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("sensitive_expires_at").toInstant(), null), key);
    }

    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private record AlertContext(String chatJid, String decisionState, boolean realActionAllowed, boolean wouldAlert,
                                String classificationStatus, String urgency, double confidence, String referenceHash,
                                boolean enqueued, boolean aiEnabled, boolean alertsEnabled, boolean killSwitchEnabled,
                                String templateId, int templateVersion, boolean templateEnabled,
                                boolean identifiesAutomation, String templateBody) { }
    private record SourceMessage(String externalId, String chatJid, String payload) { }
}
