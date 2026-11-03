package dev.hugo.watcher.simulation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Clock;
import java.time.Duration;

@Component
@ConditionalOnExpression("${watcher.simulation.enabled:true} or ${watcher.decision-producer.real-mode-enabled:false}")
public class SimulationScheduler {
    private final DebounceService debounce;
    private final SimulationService simulations;
    private final Clock clock;
    private final SimulationBatchDueRepository dueDates;
    private final boolean realModeEnabled;
    private dev.hugo.watcher.ai.ShadowMetrics metrics;

    @Autowired(required = false)
    public void configureMetrics(dev.hugo.watcher.ai.ShadowMetrics value) { this.metrics = value; }

    private dev.hugo.watcher.OperationalState operational;

    @Autowired
    public void configureOperationalState(dev.hugo.watcher.OperationalState value) { this.operational = value; }

    public SimulationScheduler(DebounceService debounce, SimulationService simulations, Clock clock,
                               SimulationBatchDueRepository dueDates,
                               @Value("${watcher.decision-producer.real-mode-enabled:false}") boolean realModeEnabled) {
        this.debounce = debounce;
        this.simulations = simulations;
        this.clock = clock;
        this.dueDates = dueDates;
        this.realModeEnabled = realModeEnabled;
    }

    @Scheduled(fixedDelayString = "${watcher.simulation.poll-delay:PT1S}")
    public void processReady() {
        if (!realModeEnabled && (operational == null || !operational.simulation())) return;
        for (Batch batch : debounce.claimReady(clock.instant())) {
            if (metrics != null) {
                // Observability must never prevent evaluation of a claimed batch.
                try {
                    dueDates.findDueAt(batch.id()).ifPresent(due -> {
                        var instant = due.toInstant();
                        var now = clock.instant();
                        if (!now.isBefore(instant)) metrics.queueWait(Duration.between(instant, now));
                    });
                } catch (RuntimeException ignored) {
                    // Ignore transient metrics failures.
                }
            }
            simulations.evaluate(batch);
        }
    }
}
