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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import dev.hugo.watcher.simulation.SimulationPersistenceRepository.Scope;
import dev.hugo.watcher.simulation.SimulationPersistenceRepository.Settings;
import dev.hugo.watcher.simulation.SimulationPersistenceRepository.Cooldowns;
import java.util.List;
import java.util.Objects;

@Service
public class SimulationService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SimulationService.class);
    private final SimulationPersistenceRepository persistence;
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

    public SimulationService(SimulationPersistenceRepository persistence, PlatformTransactionManager manager, Clock clock,
                             WatcherSimulationProperties properties, DecisionEngine decisions, PersistedPolicyRepository policies,
                             AvailabilityService availability, WatcherProperties watcher,
                             dev.hugo.watcher.ai.AiSimulationClassifier classifier,
                             dev.hugo.watcher.notification.DecisionActionProducer producer) {
        this.persistence = persistence;
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

    /** Test adapter retaining the original constructors while migration #24 is unfinished. */
    public SimulationService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                             WatcherSimulationProperties properties, DecisionEngine decisions, PersistedPolicyRepository policies,
                             AvailabilityService availability, WatcherProperties watcher,
                             dev.hugo.watcher.ai.AiSimulationClassifier classifier,
                             dev.hugo.watcher.notification.DecisionActionProducer producer) {
        this(new SimulationPersistenceRepository(jdbc), manager, clock, properties, decisions, policies,
                availability, watcher, classifier, producer);
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
            boolean existing = persistence.decisionExists(batch.id());
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
        persistence.lockIdentityMerge();
        List<Batch> current = persistence.currentBatch(batch);
        if (current.isEmpty()) throw new IllegalStateException("simulation batch missing");
        return evaluateCurrent(current.getFirst(), classification, now);
    }

    private SimulationDecision evaluateCurrent(Batch batch, Classification classification, Instant now) {
        persistence.lockDecisionKey(batch.chatJid());
        List<Scope> scope = persistence.scopes(batch.chatJid());
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
                decided.realActionAllowed(), decided.realBlockedReason(), decided.status(), decided.triageOutcome(),
                decided.notificationRecommended(), decided.replyRecommended(), decided.triageReason(),
                decided.triageBlockers());
        int inserted = persistence.insertDecision(batch.id(), decision, now,
                now.plusSeconds(properties.getAuditRetentionDays() * 86_400L),
                JSON.writeValueAsString(resolved.fields()), currentAvailability,
                verified, producer != null && producer.realModeEnabled(),
                effective.decisionPolicy().templates().containsKey(Situation.CRITICAL_URGENCY)
                    ? effective.decisionPolicy().templates().get(Situation.CRITICAL_URGENCY).reference() : null);
        List<SimulationDecision> persisted = persistence.persistedDecision(batch.id());
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
                            LOG.info("shadow_decision batch_id={} state={} classification={} reason={} diagnostic={}",
                                    batch.id(), decision.status(), completed.status(),
                                    dev.hugo.watcher.ai.ShadowMetrics.safeReason(decision.blockedReason()),
                                    dev.hugo.watcher.ai.ShadowMetrics.safeReason(completed.diagnostic()));
                        }
                    });
        }
        if (inserted == 1 && (decision.wouldAlert() || decision.wouldReply())) {
            persistence.updateSimulatedCooldown(batch.chatJid(), now);
        }
        persistence.completeBatch(batch.id());
        if (producer != null && verified.available()) producer.produce(batch.id());
        return persisted.getFirst();
    }

    private String fallbackProfile(String chat) {
        List<String> names = persistence.snapshotNames(chat);
        String name = names.isEmpty() || names.getFirst() == null ? "" : names.getFirst();
        return java.util.regex.Pattern.compile(watcher.getAllowedNameRegex(), java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(name).find() ? "WORK_ONLY" : "ANYTIME";
    }

    private DecisionPolicy loadDecisionPolicy(Batch batch) {
        List<Settings> settings = persistence.settings();
        if (settings.isEmpty()) return DecisionPolicy.disabled();
        Settings current = settings.getFirst();
        EnumMap<Situation, ResponseTemplate> templates = new EnumMap<>(Situation.class);
        java.util.EnumSet<Situation> authorized = java.util.EnumSet.noneOf(Situation.class);
        for (String situation : persistence.authorizedSituations(batch.chatJid()))
            authorized.add(Situation.valueOf(situation));
        for (var row : persistence.authorizedTemplates(batch.chatJid())) {
            Situation situation = Situation.valueOf(row.situation());
            if (situation != Situation.valueOf(row.templateSituation())) continue;
            templates.putIfAbsent(situation, new ResponseTemplate(row.templateId(), row.version(),
                    situation, row.body(), row.identifiesAutomation()));
        }
        List<Cooldowns> cooldowns = persistence.cooldowns(batch.chatJid());
        Cooldowns last = cooldowns.isEmpty() ? new Cooldowns(null, null) : cooldowns.getFirst();
        return new DecisionPolicy(operational != null && operational.classification(), operational != null && operational.simulatedAlerts(),
                operational != null && operational.simulatedReplies(), operational == null || operational.killSwitch(), authorized, templates, Duration.ofSeconds(current.simulationCooldownSeconds()),
                Duration.ofSeconds(current.realCooldownSeconds()), last.simulationAt(), last.realAt());
    }

    private static String blockedReason(Scope scope) {
        if (scope.mode() == ArchiveMode.ALL) return null;
        if (scope.archived() == null || !scope.synchronizedSnapshot()) return "archive_state_unknown";
        if (!scope.mode().accepts(scope.archived(), scope.synchronizedSnapshot())) return "archive_scope_excluded";
        return null;
    }

}
