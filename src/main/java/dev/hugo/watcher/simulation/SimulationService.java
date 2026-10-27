package dev.hugo.watcher.simulation;

import dev.hugo.watcher.ArchiveMode;
import dev.hugo.watcher.policy.EffectivePolicy;
import dev.hugo.watcher.policy.DecisionPolicy;
import dev.hugo.watcher.policy.ResponseTemplate;
import dev.hugo.watcher.policy.Situation;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SimulationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final WatcherSimulationProperties properties;
    private final DecisionEngine decisions;

    @Autowired
    public SimulationService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                             WatcherSimulationProperties properties, DecisionEngine decisions) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.properties = properties;
        this.decisions = decisions;
    }

    public SimulationService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC(), new WatcherSimulationProperties(), new DecisionEngine());
    }

    public SimulationService(JdbcTemplate jdbc) {
        this(jdbc, new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public SimulationDecision evaluate(Batch batch) {
        Objects.requireNonNull(batch, "batch");
        return Objects.requireNonNull(transactions.execute(status -> evaluateTransaction(batch, clock.instant())));
    }

    private SimulationDecision evaluateTransaction(Batch batch, Instant now) {
        lockDecisionKey(batch);
        List<Scope> scope = jdbc.query("""
                select settings.mode, snapshot.archived, coalesce(snapshot.synchronized_snapshot,false) synchronized_snapshot
                  from archive_monitor_settings settings
                  left join chat_snapshot snapshot on snapshot.chat_jid=?
                 where settings.singleton_id=1
                """, (rs, row) -> new Scope(ArchiveMode.valueOf(rs.getString("mode")), (Boolean) rs.getObject("archived"),
                rs.getBoolean("synchronized_snapshot")), batch.chatJid());
        if (scope.isEmpty()) throw new IllegalStateException("archive monitor settings missing");
        String scopeBlocked = blockedReason(scope.getFirst());
        Decision decided = scopeBlocked == null
                ? decisions.decide(batch, Classification.unavailable(), new EffectivePolicy(Map.of(), null, loadDecisionPolicy(batch)), now)
                : new Decision(false, false, null, "archive_scope_blocked", scopeBlocked, false, "archive_scope_blocked");
        SimulationDecision decision = new SimulationDecision(decided.wouldAlert(), decided.wouldReply(),
                decided.templateReference(), decided.reason(), decided.blockedReason(),
                decided.realActionAllowed(), decided.realBlockedReason(), decided.status());
        int inserted = jdbc.update("""
                insert into simulation_decisions(batch_id,would_alert,would_reply,template_id,decision_reason,blocked_reason,
                    decided_at,metadata_expires_at,real_action_allowed,real_blocked_reason,decision_state)
                values (?,?,?,?,?,?,?,?,?,?,?) on conflict (batch_id) do nothing
                """, batch.id(), decision.wouldAlert(), decision.wouldReply(), decision.templateId(), decision.decisionReason(),
                decision.blockedReason(), Timestamp.from(now), Timestamp.from(now.plusSeconds(properties.getAuditRetentionDays() * 86_400L)),
                decision.realActionAllowed(), decision.realBlockedReason(), decision.status().name());
        List<SimulationDecision> persisted = jdbc.query("""
                select would_alert,would_reply,template_id,decision_reason,blocked_reason,real_action_allowed,real_blocked_reason,decision_state
                  from simulation_decisions where batch_id=?
                """, (rs, row) -> new SimulationDecision(rs.getBoolean("would_alert"), rs.getBoolean("would_reply"),
                rs.getString("template_id"), rs.getString("decision_reason"), rs.getString("blocked_reason"),
                rs.getBoolean("real_action_allowed"), rs.getString("real_blocked_reason"),
                DecisionStatus.valueOf(rs.getString("decision_state"))), batch.id());
        if (persisted.isEmpty()) throw new IllegalStateException("simulation decision was not persisted");
        if (inserted == 1 && (decision.wouldAlert() || decision.wouldReply())) {
            jdbc.update("""
                    insert into decision_cooldowns(chat_jid,participant_jid,last_simulation_action_at)
                    values (?,?,?) on conflict(chat_jid,participant_jid) do update
                    set last_simulation_action_at=excluded.last_simulation_action_at
                    """, batch.chatJid(), "", Timestamp.from(now));
        }
        jdbc.update("update simulation_batches set status='COMPLETED', claimed_at=null where batch_id=? and status='CLAIMED'", batch.id());
        return persisted.getFirst();
    }

    private DecisionPolicy loadDecisionPolicy(Batch batch) {
        List<Settings> settings = jdbc.query("""
                select ai_classification_enabled,intelligent_alerts_enabled,auto_reply_enabled,kill_switch_enabled,
                       simulation_cooldown_seconds,real_cooldown_seconds
                  from automation_settings where singleton_id=1
                """, (rs, row) -> new Settings(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4),
                rs.getInt(5), rs.getInt(6)));
        if (settings.isEmpty()) return DecisionPolicy.disabled();
        Settings current = settings.getFirst();
        EnumMap<Situation, ResponseTemplate> templates = new EnumMap<>(Situation.class);
        jdbc.query("""
                select a.situation,t.situation template_situation,t.template_id,t.version,t.body,t.identifies_automation
                  from chat_automation_authorizations a
                  join response_templates t on t.template_id=a.template_id and t.version=a.template_version
                 where a.chat_jid=? and t.enabled=true
                 order by a.situation,t.version desc,t.template_id
                """, rs -> {
            Situation situation = Situation.valueOf(rs.getString("situation"));
            if (situation != Situation.valueOf(rs.getString("template_situation"))) return;
            templates.putIfAbsent(situation, new ResponseTemplate(rs.getString("template_id"), rs.getInt("version"),
                    situation, rs.getString("body"), rs.getBoolean("identifies_automation")));
        }, batch.chatJid());
        List<Cooldowns> cooldowns = jdbc.query("""
                select last_simulation_action_at,last_real_action_at from decision_cooldowns
                 where chat_jid=? and participant_jid=?
                """, (rs, row) -> new Cooldowns(rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(),
                rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()), batch.chatJid(), "");
        Cooldowns last = cooldowns.isEmpty() ? new Cooldowns(null, null) : cooldowns.getFirst();
        return new DecisionPolicy(current.aiClassificationEnabled(), current.intelligentAlertsEnabled(), current.autoReplyEnabled(),
                current.killSwitchEnabled(), templates.keySet(), templates, Duration.ofSeconds(current.simulationCooldownSeconds()),
                Duration.ofSeconds(current.realCooldownSeconds()), last.simulationAt(), last.realAt());
    }

    private void lockDecisionKey(Batch batch) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "decision\n" + batch.chatJid());
                statement.execute();
            }
            return null;
        });
    }

    private static String blockedReason(Scope scope) {
        if (scope.mode() == ArchiveMode.ALL) return null;
        if (scope.archived() == null || !scope.synchronizedSnapshot()) return "archive_state_unknown";
        if (!scope.mode().accepts(scope.archived(), scope.synchronizedSnapshot())) return "archive_scope_excluded";
        return null;
    }

    private record Scope(ArchiveMode mode, Boolean archived, boolean synchronizedSnapshot) { }
    private record Settings(boolean aiClassificationEnabled, boolean intelligentAlertsEnabled, boolean autoReplyEnabled,
                            boolean killSwitchEnabled, int simulationCooldownSeconds, int realCooldownSeconds) { }
    private record Cooldowns(Instant simulationAt, Instant realAt) { }
}
