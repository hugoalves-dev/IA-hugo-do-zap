package dev.hugo.watcher.simulation;

import dev.hugo.watcher.ArchiveMode;
import dev.hugo.watcher.policy.Availability;
import dev.hugo.watcher.policy.AvailabilityService;
import dev.hugo.watcher.policy.EffectivePolicy;
import dev.hugo.watcher.policy.HolidayService;
import dev.hugo.watcher.policy.PersistedPolicyRepository;
import dev.hugo.watcher.policy.PolicyResolver;
import dev.hugo.watcher.WatcherProperties;
import tools.jackson.databind.json.JsonMapper;
import java.time.ZoneId;
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
import java.util.Objects;

@Service
public class SimulationService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SimulationService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final WatcherSimulationProperties properties;
    private final DecisionEngine decisions;
    private final PersistedPolicyRepository policies;
    private final AvailabilityService availability;
    private final WatcherProperties watcher;
    private final dev.hugo.watcher.ai.AiSimulationClassifier classifier;
    private final dev.hugo.watcher.notification.DecisionActionProducer producer;
    private dev.hugo.watcher.OperationalState operational;
    private dev.hugo.watcher.ai.ShadowMetrics metrics;
    @Autowired(required = false) public void configureMetrics(dev.hugo.watcher.ai.ShadowMetrics value) { this.metrics = value; }
    @Autowired public void configureOperationalState(dev.hugo.watcher.OperationalState value) { this.operational = value; }
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    public SimulationService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                             WatcherSimulationProperties properties, DecisionEngine decisions, PersistedPolicyRepository policies,
                             AvailabilityService availability, WatcherProperties watcher,
                             dev.hugo.watcher.ai.AiSimulationClassifier classifier,
                             dev.hugo.watcher.notification.DecisionActionProducer producer) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.properties = properties;
        this.decisions = decisions;
        this.policies = policies;
        this.availability = availability;
        this.watcher = watcher;
        this.classifier = classifier;
        this.producer = producer;
    }

    public SimulationService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                             WatcherSimulationProperties properties, DecisionEngine decisions, PersistedPolicyRepository policies,
                             AvailabilityService availability, WatcherProperties watcher,
                             dev.hugo.watcher.ai.AiSimulationClassifier classifier) {
        this(jdbc,manager,clock,properties,decisions,policies,availability,watcher,classifier,null);
    }

    public SimulationService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                             WatcherSimulationProperties properties, DecisionEngine decisions, PersistedPolicyRepository policies,
                             AvailabilityService availability, WatcherProperties watcher) {
        this(jdbc, manager, clock, properties, decisions, policies, availability, watcher, null);
    }

    public SimulationService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                             WatcherSimulationProperties properties, DecisionEngine decisions) {
        this(jdbc, manager, clock, properties, decisions, new PersistedPolicyRepository(jdbc, new PolicyResolver()),
                new AvailabilityService(new HolidayService(jdbc)), new WatcherProperties());
    }

    public SimulationService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC(), new WatcherSimulationProperties(), new DecisionEngine());
    }

    public SimulationService(JdbcTemplate jdbc) {
        this(jdbc, new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public SimulationDecision evaluate(Batch batch) {
        Objects.requireNonNull(batch, "batch");
        long started = System.nanoTime();
        try {
            // Provider requests run without a database transaction or advisory lock held.
            boolean existing = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from simulation_decisions where batch_id=?)", Boolean.class, batch.id()));
            Classification classification = classifier == null || existing ? Classification.unavailable() : classifier.classify(batch);
            long persistStarted = System.nanoTime();
            try {
                return Objects.requireNonNull(transactions.execute(
                        status -> evaluateTransaction(batch, classification, clock.instant())));
            } finally {
                if (metrics != null)
                    metrics.persistenceLatency(Duration.ofNanos(System.nanoTime() - persistStarted));
            }
        } finally {
            if (metrics != null) metrics.decisionLatency(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private SimulationDecision evaluateTransaction(Batch batch, Classification classification, Instant now) {
        jdbc.query("select pg_advisory_xact_lock(947221, 2)", rs -> { });
        List<Batch> current = jdbc.query("select chat_jid,participant_jid from simulation_batches where batch_id=?",
                (rs, row) -> new Batch(batch.id(), rs.getString(1), rs.getString(2), batch.messages()), batch.id());
        if (current.isEmpty()) throw new IllegalStateException("simulation batch missing");
        return evaluateCurrent(current.getFirst(), classification, now);
    }

    private SimulationDecision evaluateCurrent(Batch batch, Classification classification, Instant now) {
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
        EffectivePolicy resolved = policies.forChat(batch.chatJid(), fallbackProfile(batch.chatJid()));
        Availability currentAvailability = availability.at(now, ZoneId.of(watcher.getTimezone()), resolved);
        EffectivePolicy effective = new EffectivePolicy(resolved.fields(), resolved.temporaryAvailability(),
                loadDecisionPolicy(batch), currentAvailability);
        Classification verified = classifier == null ? classification : classifier.revalidate(batch, classification);
        Decision decided = scopeBlocked == null
                ? decisions.decide(batch, verified, effective, now)
                : new Decision(false, false, null, "archive_scope_blocked", scopeBlocked, false, "archive_scope_blocked");
        SimulationDecision decision = new SimulationDecision(decided.wouldAlert(), decided.wouldReply(),
                decided.templateReference(), decided.reason(), decided.blockedReason(),
                decided.realActionAllowed(), decided.realBlockedReason(), decided.status());
        int inserted = jdbc.update("""
                insert into simulation_decisions(batch_id,would_alert,would_reply,template_id,decision_reason,blocked_reason,
                    decided_at,metadata_expires_at,real_action_allowed,real_blocked_reason,decision_state,policy_snapshot,availability_state,availability_source,availability_reason,
                    classification_status,classification_urgency,classification_confidence,intelligent_alert_reference_hash,production_mode,alert_template_reference,classification_diagnostic)
                values (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?) on conflict (batch_id) do nothing
                """, batch.id(), decision.wouldAlert(), decision.wouldReply(), decision.templateId(), decision.decisionReason(),
                decision.blockedReason(), Timestamp.from(now), Timestamp.from(now.plusSeconds(properties.getAuditRetentionDays() * 86_400L)),
                decision.realActionAllowed(), decision.realBlockedReason(), decision.status().name(),
                JSON.writeValueAsString(resolved.fields()), currentAvailability.state().name(),
                currentAvailability.source().name(), currentAvailability.reason(),
                verified.status().name(),verified.urgency().name(),verified.confidence(),verified.personalReferenceHash(),producer != null && producer.realModeEnabled(),
                effective.decisionPolicy().templates().containsKey(Situation.CRITICAL_URGENCY)
                        ? effective.decisionPolicy().templates().get(Situation.CRITICAL_URGENCY).reference() : null, verified.diagnostic());
        List<SimulationDecision> persisted = jdbc.query("""
                select would_alert,would_reply,template_id,decision_reason,blocked_reason,real_action_allowed,real_blocked_reason,decision_state
                  from simulation_decisions where batch_id=?
                """, (rs, row) -> new SimulationDecision(rs.getBoolean("would_alert"), rs.getBoolean("would_reply"),
                rs.getString("template_id"), rs.getString("decision_reason"), rs.getString("blocked_reason"),
                rs.getBoolean("real_action_allowed"), rs.getString("real_blocked_reason"),
                DecisionStatus.valueOf(rs.getString("decision_state"))), batch.id());
        if (persisted.isEmpty()) throw new IllegalStateException("simulation decision was not persisted");
        if (inserted == 1) {
            Classification completed = verified;
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() {
                            if (metrics != null) {
                                metrics.decision(decision.blockedReason(), completed.diagnostic());
                                if (scopeBlocked == null) metrics.eligibleBatch();
                            }
                            LOG.info("shadow_decision batch_id={} state={} classification={}", batch.id(), decision.status(), completed.status());
                        }
                    });
        }
        if (inserted == 1 && (decision.wouldAlert() || decision.wouldReply())) {
            jdbc.update("""
                    insert into decision_cooldowns(chat_jid,participant_jid,last_simulation_action_at)
                    values (?,?,?) on conflict(chat_jid,participant_jid) do update
                    set last_simulation_action_at=excluded.last_simulation_action_at
                    """, batch.chatJid(), "", Timestamp.from(now));
        }
        jdbc.update("update simulation_batches set status='COMPLETED', claimed_at=null where batch_id=? and status='CLAIMED'", batch.id());
        if (producer != null && verified.available()) producer.produce(batch.id());
        return persisted.getFirst();
    }

    private String fallbackProfile(String chat) {
        List<String> names = jdbc.query("select display_name from chat_snapshot where chat_jid=?", (rs, row) -> rs.getString(1), chat);
        String name = names.isEmpty() || names.getFirst() == null ? "" : names.getFirst();
        return java.util.regex.Pattern.compile(watcher.getAllowedNameRegex(), java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(name).find() ? "WORK_ONLY" : "ANYTIME";
    }

    private DecisionPolicy loadDecisionPolicy(Batch batch) {
        List<Settings> settings = jdbc.query("""
                select simulation_cooldown_seconds,real_cooldown_seconds
                  from automation_settings where singleton_id=1
                """, (rs, row) -> new Settings(rs.getInt(1), rs.getInt(2)));
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
        return new DecisionPolicy(operational != null && operational.classification(), operational != null && operational.alerts(),
                operational != null && operational.replies(), operational == null || operational.killSwitch(), templates.keySet(), templates, Duration.ofSeconds(current.simulationCooldownSeconds()),
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
    private record Settings(int simulationCooldownSeconds, int realCooldownSeconds) { }
    private record Cooldowns(Instant simulationAt, Instant realAt) { }
}
