package dev.hugo.watcher.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Durable WhatsApp action admission and authorization projections.
 * Advisory locks, queue insert and the decision tombstone must remain in the
 * caller's existing transaction; no network request runs from this repository.
 */
@Repository
public class WhatsAppActionPersistenceRepository {
    private final JdbcTemplate jdbc;

    public WhatsAppActionPersistenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockDecision(UUID decisionId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 947221))", rs -> { }, decisionId.toString());
    }

    public boolean decisionExists(UUID decisionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from simulation_decisions where batch_id=?)", Boolean.class, decisionId));
    }

    public List<Queued> queuedForDecision(UUID decisionId) {
        return jdbc.query("select action_id,status,expires_at from whatsapp_action_outbox where decision_id=?",
                (rs, row) -> new Queued(rs.getObject("action_id", UUID.class), rs.getString("status"),
                        rs.getTimestamp("expires_at").toInstant()), decisionId);
    }

    public boolean alreadyEnqueued(UUID decisionId) {
        return Boolean.TRUE.equals(jdbc.query("select whatsapp_action_enqueued from simulation_decisions where batch_id=?",
                rs -> rs.next() && rs.getBoolean(1), decisionId));
    }

    public void lockChat(String chatJid) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 947222))", rs -> { }, chatJid);
    }

    public int activeActionsForChat(String chatJid) {
        Integer active = jdbc.queryForObject("""
                select count(*) from whatsapp_action_outbox where chat_jid=?
                  and status in ('PENDING','DISPATCHED','UNKNOWN')
                """, Integer.class, chatJid);
        return active == null ? 0 : active;
    }

    public void insertAction(UUID actionId, UUID decisionId, Authorization auth, Instant createdAt) {
        jdbc.update("""
                insert into whatsapp_action_outbox(action_id,decision_id,chat_jid,participant_jid,situation,
                    template_id,template_version,body,status,created_at,updated_at,expires_at)
                values (?,?,?,?,?,?,?,?,'PENDING',?,?,?)
                """, actionId, decisionId, auth.chatJid(), auth.participantJid(),
                auth.situation(), auth.templateId(), auth.templateVersion(), auth.body(),
                Timestamp.from(createdAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt.plusSeconds(7 * 86_400L)));
    }

    public void markEnqueued(UUID decisionId) {
        jdbc.update("update simulation_decisions set whatsapp_action_enqueued=true where batch_id=?", decisionId);
    }

    public List<Action> dispatchedAction(UUID actionId, Instant now) {
        return jdbc.query("""
                select decision_id,chat_jid,participant_jid,situation,template_id,template_version
                from whatsapp_action_outbox where action_id=? and status='DISPATCHED' and expires_at>?
                """, (rs, row) -> new Action(rs.getObject("decision_id", UUID.class), rs.getString("chat_jid"),
                rs.getString("participant_jid"), rs.getString("situation"), rs.getString("template_id"),
                rs.getInt("template_version")), actionId, Timestamp.from(now));
    }

    public List<Authorization> authorizations(UUID decisionId) {
        return jdbc.query("""
                select b.chat_jid,b.participant_jid,t.situation,t.template_id,t.version,t.body,
                       d.decision_state,d.real_action_allowed,d.would_reply,d.would_alert,
                       s.real_cooldown_seconds,t.enabled,t.identifies_automation,c.last_real_action_at
                  from simulation_decisions d join simulation_batches b on b.batch_id=d.batch_id
                  join response_templates t on d.template_id=t.template_id || '@v' || t.version::text
                  cross join automation_settings s
                  left join decision_cooldowns c on c.chat_jid=b.chat_jid and c.participant_jid=''
                 where d.batch_id=? and s.singleton_id=1
                """, (rs, row) -> new Authorization(rs.getString("chat_jid"), rs.getString("participant_jid"),
                rs.getString("situation"), rs.getString("template_id"), rs.getInt("version"), rs.getString("body"),
                rs.getString("decision_state"), rs.getBoolean("real_action_allowed"), rs.getBoolean("would_reply"),
                rs.getBoolean("would_alert"), rs.getInt("real_cooldown_seconds"),
                rs.getTimestamp("last_real_action_at") == null ? null : rs.getTimestamp("last_real_action_at").toInstant(),
                rs.getBoolean("enabled"), rs.getBoolean("identifies_automation")), decisionId);
    }

    public int authorizationCount(Authorization auth) {
        Integer optedIn = jdbc.queryForObject("""
                select count(*) from chat_automation_authorizations
                 where chat_jid=? and situation=? and template_id=? and template_version=?
                """, Integer.class, auth.chatJid(), auth.situation(), auth.templateId(), auth.templateVersion());
        return optedIn == null ? 0 : optedIn;
    }

    public record Queued(UUID id, String status, Instant expiresAt) { }
    public record Action(UUID decisionId, String chatJid, String participantJid, String situation,
                         String templateId, int templateVersion) { }
    public record Authorization(String chatJid, String participantJid, String situation, String templateId,
                                int templateVersion, String body, String decisionState, boolean realActionAllowed,
                                boolean wouldReply, boolean wouldAlert, int cooldownSeconds, Instant lastRealActionAt,
                                boolean templateEnabled, boolean identifiesAutomation) { }
}
