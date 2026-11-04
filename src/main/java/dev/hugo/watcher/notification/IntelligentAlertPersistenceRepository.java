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
                select b.chat_jid,d.decision_state,d.real_action_allowed,d.would_alert,d.notification_recommended,
                       d.classification_status,d.classification_urgency,d.classification_confidence,
                       d.intelligent_alert_reference_hash,d.intelligent_alert_enqueued,
                       exists(select 1 from chat_automation_authorizations critical_auth
                               join response_templates critical_template
                                 on critical_template.template_id=critical_auth.template_id
                                and critical_template.version=critical_auth.template_version
                                and critical_template.situation=critical_auth.situation
                              where critical_auth.chat_jid=b.chat_jid
                                and critical_auth.situation='CRITICAL_URGENCY'
                                and d.alert_template_reference=critical_template.template_id || '@v' || critical_template.version::text
                                and critical_template.enabled and critical_template.identifies_automation
                                and lower(ltrim(critical_template.body)) like 'resposta automática:%') as critical_template_eligible,
                       exists(select 1 from chat_automation_authorizations a
                               where a.chat_jid=b.chat_jid
                                 and exists(select 1 from jsonb_array_elements_text(e.result->'situations') s(value)
                                             where s.value=a.situation)) as authorized_situation
                  from simulation_decisions d
                  join simulation_batches b on b.batch_id=d.batch_id
                  join simulation_ai_evaluations e on e.batch_id=d.batch_id and e.result is not null
                 where d.batch_id=? and d.triage_outcome in ('NOTIFY_ONLY','NOTIFY_AND_REPLY')
                   and exists(select 1 from chat_automation_authorizations a
                               where a.chat_jid=b.chat_jid
                                 and ((d.classification_urgency='CRITICAL' and a.situation='CRITICAL_URGENCY')
                                   or (d.classification_urgency<>'CRITICAL'
                                       and exists(select 1 from jsonb_array_elements_text(e.result->'situations') s(value)
                                                   where s.value=a.situation))))
                """, (rs, row) -> new AlertContext(rs.getString("chat_jid"), rs.getString("decision_state"),
                rs.getBoolean("real_action_allowed"), rs.getBoolean("would_alert"),
                rs.getBoolean("notification_recommended"),
                rs.getString("classification_status"), rs.getString("classification_urgency"),
                rs.getDouble("classification_confidence"), rs.getString("intelligent_alert_reference_hash"),
                rs.getBoolean("intelligent_alert_enqueued"), rs.getBoolean("authorized_situation"),
                rs.getBoolean("critical_template_eligible")), decisionId);
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
                               boolean notificationRecommended, String classificationStatus, String urgency,
                               double confidence, String referenceHash, boolean enqueued, boolean authorizedSituation,
                               boolean criticalTemplateEligible) { }

    public record SourceMessage(String externalId, String chatJid, String payload) { }
}
