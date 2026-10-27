package dev.hugo.watcher.simulation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class SimulationScheduler {
    private final DebounceService debounce;
    private final SimulationService simulations;
    private final Clock clock;
    public SimulationScheduler(DebounceService debounce, SimulationService simulations, Clock clock) {
        this.debounce = debounce;
        this.simulations = simulations;
        this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${watcher.simulation.poll-delay:PT1S}")
    public void processReady() {
        for (Batch batch : debounce.claimReady(clock.instant())) simulations.evaluate(batch);
    }
}
