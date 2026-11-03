package dev.hugo.watcher.notification;

import dev.hugo.watcher.OutboxAction;
import static dev.hugo.watcher.notification.IntelligentAlertService.ACTION_TYPE;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PostgreSQL-native eligibility and outbox persistence; transactions are owned by IntelligentAlertService. */
@Repository
public class IntelligentAlertPersistenceRepository {
    private final JdbcTemplate jdbc;

    public IntelligentAlertPersistenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockDecision(UUID decisionId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 947223))", rs -> { }, decisionId.toString());
    }

    public boolean alreadyEnqueued(UUID decisionId) {
        return Boolean.TRUE.equals(jdbc.query("select intelligent_alert_enqueued from simulation_decisions where batch_id=?",
                rs -> rs.next() && rs.getBoolean(1), decisionId));
    }

    public void markEnqueued(UUID decisionId) {
        jdbc.update("update simulation_decisions set intelligent_alert_enqueued=true where batch_id=?", decisionId);
    }

    public int processingCount(OutboxAction action) {
        Integer pending = jdbc.queryForObject("select count(*) from outbox_actions where id=? and action_key=? and action_type=? and status='PROCESSING'",
                Integer.class, action.id(), action.actionKey(), ACTION_TYPE);
        return pending == null ? 0 : pending;
    }

    public List<AlertContext> eligibleContexts(UUID decisionId) {
        return jdbc.query("""
                select b.chat_jid,d.decision_state,d.real_action_allowed,d.would_alert,
                       d.classification_status,d.classification_urgency,d.classification_confidence,
                       d.intelligent_alert_reference_hash,d.intelligent_alert_enqueued,
                       t.template_id,t.version,t.enabled,t.identifies_automation,t.body
                  from simulation_decisions d
                  join simulation_batches b on b.batch_id=d.batch_id
                  cross join automation_settings s
                  join chat_automation_authorizations a on a.chat_jid=b.chat_jid
                    and a.situation='CRITICAL_URGENCY'
                  join response_templates t on t.template_id=a.template_id and t.version=a.template_version
                    and t.situation=a.situation and d.alert_template_reference=t.template_id || '@v' || t.version::text
                 where d.batch_id=? and s.singleton_id=1
                 order by t.template_id,t.version desc limit 1
                """, (rs, row) -> new AlertContext(rs.getString("chat_jid"), rs.getString("decision_state"),
                rs.getBoolean("real_action_allowed"), rs.getBoolean("would_alert"),
                rs.getString("classification_status"), rs.getString("classification_urgency"),
                rs.getDouble("classification_confidence"), rs.getString("intelligent_alert_reference_hash"),
                rs.getBoolean("intelligent_alert_enqueued"),
                rs.getString("template_id"), rs.getInt("version"), rs.getBoolean("enabled"),
                rs.getBoolean("identifies_automation"), rs.getString("body")), decisionId);
    }

    public List<SourceMessage> sourceMessages(UUID decisionId) {
        return jdbc.query("select external_id,chat_jid,payload::text from simulation_batch_messages where batch_id=?",
                (rs, row) -> new SourceMessage(rs.getString("external_id"), rs.getString("chat_jid"), rs.getString("payload")), decisionId);
    }

    public int referenceCount(String chatJid, String externalId, Instant now) {
        Integer references = jdbc.queryForObject("""
                            select count(*) from whatsapp_owner_message_references
                             where chat_jid=? and external_id=? and expires_at > ?
                            """, Integer.class, chatJid, externalId, Timestamp.from(now));
        return references == null ? 0 : references;
    }

    public boolean isOutboxAlreadyProcessing(UUID decisionId) {
        Integer count = jdbc.queryForObject("select count(*) from outbox_actions where action_key=? and status='PROCESSING'",
                Integer.class, ACTION_TYPE + ":" + decisionId);
        return count != null && count > 0;
    }

    public List<OutboxAction> findOutbox(String key) {
        return jdbc.query("""
                select id,action_key,action_type,payload::text,pending_snapshot::text,attempts,created_at,sensitive_expires_at
                  from outbox_actions where action_key=?
                """, (rs, row) -> new OutboxAction(rs.getLong("id"), rs.getString("action_key"), rs.getString("action_type"),
                rs.getString("payload"), rs.getString("pending_snapshot"), rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("sensitive_expires_at").toInstant(), null), key);
    }

    public record AlertContext(String chatJid, String decisionState, boolean realActionAllowed, boolean wouldAlert,
                               String classificationStatus, String urgency, double confidence, String referenceHash,
                               boolean enqueued, String templateId, int templateVersion, boolean templateEnabled,
                               boolean identifiesAutomation, String templateBody) { }

    public record SourceMessage(String externalId, String chatJid, String payload) { }
}
