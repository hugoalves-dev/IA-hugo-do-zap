package dev.hugo.watcher.simulation;

import dev.hugo.watcher.ArchiveMode;
import dev.hugo.watcher.policy.Availability;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transactional SQL projections and writes for simulation decisions. */
@Repository
public class SimulationPersistenceRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    public SimulationPersistenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean decisionExists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from simulation_decisions where batch_id=?)", Boolean.class, id));
    }

    public void lockIdentityMerge() {
        jdbc.query("select pg_advisory_xact_lock(947221, 2)", rs -> { });
    }

    public List<Batch> currentBatch(Batch batch) {
        return jdbc.query("select chat_jid,participant_jid from simulation_batches where batch_id=?",
                (rs,row) -> new Batch(batch.id(), rs.getString(1), rs.getString(2), batch.messages()), batch.id());
    }

    public void lockDecisionKey(String chat) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "decision\n" + chat);
                statement.execute();
            }
            return null;
        });
    }

    public List<Scope> scopes(String chat) {
        return jdbc.query("""
                select settings.mode, snapshot.archived, coalesce(snapshot.synchronized_snapshot,false) synchronized_snapshot
                  from archive_monitor_settings settings
                  left join chat_snapshot snapshot on snapshot.chat_jid=?
                 where settings.singleton_id=1
                """, (rs, row) -> new Scope(ArchiveMode.valueOf(rs.getString("mode")),
                (Boolean) rs.getObject("archived"), rs.getBoolean("synchronized_snapshot")), chat);
    }

    public int insertDecision(UUID batchId, SimulationDecision decision, Instant now, Instant metadataExpiry,
                              String policyJson, Availability availability, Classification classification,
                              boolean productionMode, String alertTemplate) {
        return jdbc.update("""
                insert into simulation_decisions(batch_id,would_alert,would_reply,template_id,decision_reason,blocked_reason,
                    decided_at,metadata_expires_at,real_action_allowed,real_blocked_reason,decision_state,policy_snapshot,availability_state,availability_source,availability_reason,
                    classification_status,classification_urgency,classification_confidence,intelligent_alert_reference_hash,production_mode,alert_template_reference,classification_diagnostic,
                    triage_outcome,notification_recommended,reply_recommended,triage_reason,triage_blockers)
                values (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb) on conflict (batch_id) do nothing
                """, batchId, decision.wouldAlert(), decision.wouldReply(), decision.templateId(), decision.decisionReason(),
                decision.blockedReason(), Timestamp.from(now), Timestamp.from(metadataExpiry),
                decision.realActionAllowed(), decision.realBlockedReason(), decision.status().name(),
                policyJson, availability.state().name(), availability.source().name(), availability.reason(),
                classification.status().name(), classification.urgency().name(), classification.confidence(),
                classification.personalReferenceHash(), productionMode, alertTemplate, classification.diagnostic(),
                decision.triageOutcome().name(), decision.notificationRecommended(), decision.replyRecommended(),
                decision.triageReason(), JSON.writeValueAsString(decision.triageBlockers()));
    }

    public List<SimulationDecision> persistedDecision(UUID id) {
        return jdbc.query("""
                select would_alert,would_reply,template_id,decision_reason,blocked_reason,real_action_allowed,real_blocked_reason,decision_state,
                       triage_outcome,notification_recommended,reply_recommended,triage_reason,triage_blockers::text
                  from simulation_decisions where batch_id=?
                """, (rs, row) -> new SimulationDecision(rs.getBoolean("would_alert"), rs.getBoolean("would_reply"),
                rs.getString("template_id"), rs.getString("decision_reason"), rs.getString("blocked_reason"),
                rs.getBoolean("real_action_allowed"), rs.getString("real_blocked_reason"),
                DecisionStatus.valueOf(rs.getString("decision_state")), TriageOutcome.valueOf(rs.getString("triage_outcome")),
                rs.getBoolean("notification_recommended"), rs.getBoolean("reply_recommended"), rs.getString("triage_reason"),
                JSON.readValue(rs.getString("triage_blockers"), List.class)), id);
    }

    public void updateSimulatedCooldown(String chat, Instant now) {
        jdbc.update("""
                insert into decision_cooldowns(chat_jid,participant_jid,last_simulation_action_at)
                values (?,?,?) on conflict(chat_jid,participant_jid) do update
                set last_simulation_action_at=excluded.last_simulation_action_at
                """, chat, "", Timestamp.from(now));
    }

    public void completeBatch(UUID id) {
        jdbc.update("update simulation_batches set status='COMPLETED', claimed_at=null where batch_id=? and status='CLAIMED'", id);
    }

    public List<String> snapshotNames(String chat) {
        return jdbc.query("select display_name from chat_snapshot where chat_jid=?", (rs, row) -> rs.getString(1), chat);
    }

    public List<Settings> settings() {
        return jdbc.query("""
                select simulation_cooldown_seconds,real_cooldown_seconds
                  from automation_settings where singleton_id=1
                """, (rs, row) -> new Settings(rs.getInt(1), rs.getInt(2)));
    }

    public List<TemplateRow> authorizedTemplates(String chat) {
        return jdbc.query("""
                select a.situation,t.situation template_situation,t.template_id,t.version,t.body,t.identifies_automation
                  from chat_automation_authorizations a
                  join response_templates t on t.template_id=a.template_id and t.version=a.template_version
                 where a.chat_jid=? and t.enabled=true
                 order by a.situation,t.version desc,t.template_id
                """, (rs,row) -> new TemplateRow(rs.getString("situation"),rs.getString("template_situation"),
                    rs.getString("template_id"),rs.getInt("version"),rs.getString("body"),
                    rs.getBoolean("identifies_automation")),chat);
    }

    public List<String> authorizedSituations(String chat) {
        return jdbc.query("select distinct situation from chat_automation_authorizations where chat_jid=? order by situation",
                (rs, row) -> rs.getString("situation"), chat);
    }

    public List<Cooldowns> cooldowns(String chat) {
        return jdbc.query("""
                select last_simulation_action_at,last_real_action_at from decision_cooldowns
                 where chat_jid=? and participant_jid=?
                """, (rs, row) -> new Cooldowns(rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(),
                rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()), chat, "");
    }

    public record Scope(ArchiveMode mode, Boolean archived, boolean synchronizedSnapshot) { }
    public record Settings(int simulationCooldownSeconds, int realCooldownSeconds) { }
    public record Cooldowns(Instant simulationAt, Instant realAt) { }
    public record TemplateRow(String situation, String templateSituation, String templateId,
                              int version, String body, boolean identifiesAutomation) { }
}
