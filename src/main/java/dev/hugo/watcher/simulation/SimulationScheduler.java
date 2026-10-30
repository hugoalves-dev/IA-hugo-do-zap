package dev.hugo.watcher.simulation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
@ConditionalOnExpression("${watcher.simulation.enabled:true} or ${watcher.decision-producer.real-mode-enabled:false}")
public class SimulationScheduler {
    private final DebounceService debounce;
    private final SimulationService simulations;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final boolean realModeEnabled;
    private dev.hugo.watcher.ai.ShadowMetrics metrics;
    @Autowired(required = false) public void configureMetrics(dev.hugo.watcher.ai.ShadowMetrics value) { this.metrics = value; }
    private dev.hugo.watcher.OperationalState operational;
    @Autowired public void configureOperationalState(dev.hugo.watcher.OperationalState value) { this.operational = value; }

    @Autowired
    public SimulationScheduler(DebounceService debounce, SimulationService simulations, Clock clock, JdbcTemplate jdbc,
                              @Value("${watcher.decision-producer.real-mode-enabled:false}") boolean realModeEnabled) {
        this.debounce = debounce;
        this.simulations = simulations;
        this.clock = clock;
        this.jdbc = jdbc;
        this.realModeEnabled = realModeEnabled;
    }

    public SimulationScheduler(DebounceService debounce, SimulationService simulations, Clock clock, JdbcTemplate jdbc) {
        this(debounce, simulations, clock, jdbc, false);
    }
    @Scheduled(fixedDelayString = "${watcher.simulation.poll-delay:PT1S}")
    public void processReady() {
        if (!realModeEnabled) {
            if (operational == null || !operational.simulation()) return;
        }
        for (Batch batch : debounce.claimReady(clock.instant())) {
            if (metrics != null) {
                // Queue time covers the READY deadline and sequential backlog, including the
                // time claimed batches spend waiting their turn; never query message content.
                try {
                    java.sql.Timestamp due = jdbc.queryForObject(
                            "select due_at from simulation_batches where batch_id=?", java.sql.Timestamp.class, batch.id());
                    if (due != null && !clock.instant().isBefore(due.toInstant()))
                        metrics.queueWait(java.time.Duration.between(due.toInstant(), clock.instant()));
                } catch (RuntimeException ignored) {
                    // Observability must never prevent evaluation of a claimed batch.
                }
            }
            simulations.evaluate(batch);
        }
    }
}
