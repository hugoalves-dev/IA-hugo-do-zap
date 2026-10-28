package dev.hugo.watcher.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Queues only actions backed by a persisted decision and current explicit authorization. */
@Service
public class WhatsAppActionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean realSendEnabled;

    @Autowired
    public WhatsAppActionService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                                 @Value("${watcher.whatsapp-actions.real-send-enabled:false}") boolean realSendEnabled) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.realSendEnabled = realSendEnabled;
    }

    public ActionResult enqueue(UUID persistedDecisionId) {
        if (!realSendEnabled) return ActionResult.rejected("REAL_ACTION_DISABLED");
        if (persistedDecisionId == null) return ActionResult.rejected("DECISION_NOT_FOUND");
        return transactions.execute(status -> {
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 947221))", rs -> { }, persistedDecisionId.toString());
            Boolean decisionExists = jdbc.queryForObject("select exists(select 1 from simulation_decisions where batch_id=?)",
                    Boolean.class, persistedDecisionId);
            if (!Boolean.TRUE.equals(decisionExists)) return ActionResult.rejected("DECISION_NOT_FOUND");
            List<Queued> prior = jdbc.query("select action_id,status,expires_at from whatsapp_action_outbox where decision_id=?",
                    (rs, row) -> new Queued(rs.getObject("action_id", UUID.class), rs.getString("status"),
                            rs.getTimestamp("expires_at").toInstant()), persistedDecisionId);
            if (!prior.isEmpty()) {
                Queued queued = prior.getFirst();
                boolean active = queued.status().equals("PENDING") || queued.status().equals("DISPATCHED") || queued.status().equals("UNKNOWN");
                return active && queued.expiresAt().isAfter(clock.instant())
                        ? ActionResult.accepted(queued.id()) : ActionResult.rejected("DECISION_ALREADY_PROCESSED");
            }
            Boolean alreadyEnqueued = jdbc.query("select whatsapp_action_enqueued from simulation_decisions where batch_id=?",
                    rs -> rs.next() && rs.getBoolean(1), persistedDecisionId);
            if (Boolean.TRUE.equals(alreadyEnqueued)) return ActionResult.rejected("DECISION_ALREADY_PROCESSED");

            Authorization authorization = currentAuthorization(persistedDecisionId, clock.instant());
            if (authorization == null) return ActionResult.rejected("AUTHORIZATION_OR_POLICY_BLOCKED");
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 947222))", rs -> { }, authorization.chatJid());
            authorization = currentAuthorization(persistedDecisionId, clock.instant());
            if (authorization == null) return ActionResult.rejected("AUTHORIZATION_OR_POLICY_BLOCKED");
            Integer active = jdbc.queryForObject("""
                    select count(*) from whatsapp_action_outbox where chat_jid=?
                      and status in ('PENDING','DISPATCHED','UNKNOWN')
                    """, Integer.class, authorization.chatJid());
            if (active != null && active > 0) return ActionResult.rejected("ACTION_ALREADY_IN_FLIGHT");
            UUID actionId = UUID.randomUUID();
            Instant createdAt = clock.instant();
            jdbc.update("""
                    insert into whatsapp_action_outbox(action_id,decision_id,chat_jid,participant_jid,situation,
                        template_id,template_version,body,status,created_at,updated_at,expires_at)
                    values (?,?,?,?,?,?,?,?,'PENDING',?,?,?)
                    """, actionId, persistedDecisionId, authorization.chatJid(), authorization.participantJid(),
                    authorization.situation(), authorization.templateId(), authorization.templateVersion(),
                    authorization.body(), Timestamp.from(createdAt), Timestamp.from(createdAt),
                    Timestamp.from(createdAt.plusSeconds(7 * 86_400L)));
            jdbc.update("update simulation_decisions set whatsapp_action_enqueued=true where batch_id=?", persistedDecisionId);
            return ActionResult.accepted(actionId);
        });
    }

    /** Reads all authorization inputs again immediately before any Evolution request. */
    public AuthorizedAction revalidate(UUID actionId, Instant now) {
        if (actionId == null || now == null || !realSendEnabled) return null;
        List<Action> actions = jdbc.query("""
                select decision_id,chat_jid,participant_jid,situation,template_id,template_version
                from whatsapp_action_outbox where action_id=? and status='DISPATCHED' and expires_at>?
                """, (rs, row) -> new Action(rs.getObject("decision_id", UUID.class), rs.getString("chat_jid"),
                rs.getString("participant_jid"), rs.getString("situation"), rs.getString("template_id"),
                rs.getInt("template_version")), actionId, Timestamp.from(now));
        if (actions.isEmpty()) return null;
        Action expected = actions.getFirst();
        Authorization latest = currentAuthorization(expected.decisionId(), now);
        if (latest == null || !latest.chatJid().equals(expected.chatJid())
                || !latest.participantJid().equals(expected.participantJid())
                || !latest.situation().equals(expected.situation())
                || !latest.templateId().equals(expected.templateId())
                || latest.templateVersion() != expected.templateVersion()) return null;
        return new AuthorizedAction(actionId, expected.decisionId(), expected.chatJid(), latest.body());
    }

    private Authorization currentAuthorization(UUID decisionId, Instant now) {
        List<Authorization> rows = jdbc.query("""
                select b.chat_jid,b.participant_jid,t.situation,t.template_id,t.version,t.body,
                       d.decision_state,d.real_action_allowed,d.would_reply,d.would_alert,
                       s.kill_switch_enabled,s.auto_reply_enabled,s.intelligent_alerts_enabled,s.real_cooldown_seconds,
                       t.enabled,t.identifies_automation,
                       c.last_real_action_at
                  from simulation_decisions d join simulation_batches b on b.batch_id=d.batch_id
                  join response_templates t on d.template_id=t.template_id || '@v' || t.version::text
                  cross join automation_settings s
                  left join decision_cooldowns c on c.chat_jid=b.chat_jid and c.participant_jid=''
                 where d.batch_id=? and s.singleton_id=1
                """, (rs, row) -> new Authorization(rs.getString("chat_jid"), rs.getString("participant_jid"),
                rs.getString("situation"), rs.getString("template_id"), rs.getInt("version"), rs.getString("body"),
                rs.getString("decision_state"), rs.getBoolean("real_action_allowed"), rs.getBoolean("would_reply"),
                rs.getBoolean("would_alert"), rs.getBoolean("kill_switch_enabled"), rs.getBoolean("auto_reply_enabled"),
                rs.getBoolean("intelligent_alerts_enabled"), rs.getInt("real_cooldown_seconds"),
                rs.getTimestamp("last_real_action_at") == null ? null : rs.getTimestamp("last_real_action_at").toInstant(),
                rs.getBoolean("enabled"), rs.getBoolean("identifies_automation")), decisionId);
        if (rows.isEmpty()) return null;
        Authorization a = rows.getFirst();
        if (!a.realActionAllowed() || !"WOULD_ACT".equals(a.decisionState()) || a.killSwitchEnabled()
                || !a.templateEnabled() || !a.identifiesAutomation()) return null;
        if (a.wouldReply() == a.wouldAlert()) return null;
        if (a.wouldReply() && !a.autoReplyEnabled() || a.wouldAlert() && !a.intelligentAlertsEnabled()) return null;
        if (!a.body().stripLeading().toLowerCase(java.util.Locale.ROOT).startsWith("resposta automática:")) return null;
        Integer optedIn = jdbc.queryForObject("""
                select count(*) from chat_automation_authorizations
                 where chat_jid=? and situation=? and template_id=? and template_version=?
                """, Integer.class, a.chatJid(), a.situation(), a.templateId(), a.templateVersion());
        if (optedIn == null || optedIn != 1) return null;
        if (a.lastRealActionAt() != null && a.lastRealActionAt().plusSeconds(a.cooldownSeconds()).isAfter(now)) return null;
        return a;
    }

    public record AuthorizedAction(UUID actionId, UUID decisionId, String chatJid, String body) { }
    private record Queued(UUID id, String status, Instant expiresAt) { }
    private record Action(UUID decisionId, String chatJid, String participantJid, String situation,
                          String templateId, int templateVersion) { }
    private record Authorization(String chatJid, String participantJid, String situation, String templateId,
                                 int templateVersion, String body, String decisionState, boolean realActionAllowed,
                                 boolean wouldReply, boolean wouldAlert, boolean killSwitchEnabled,
                                 boolean autoReplyEnabled, boolean intelligentAlertsEnabled, int cooldownSeconds,
                                 Instant lastRealActionAt, boolean templateEnabled, boolean identifiesAutomation) {
    }
}
