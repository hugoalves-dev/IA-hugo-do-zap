package dev.hugo.watcher.simulation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
@ConditionalOnProperty(prefix = "watcher.simulation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SimulationScheduler {
    private final DebounceService debounce;
    private final SimulationService simulations;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    public SimulationScheduler(DebounceService debounce, SimulationService simulations, Clock clock, JdbcTemplate jdbc) {
        this.debounce = debounce;
        this.simulations = simulations;
        this.clock = clock;
        this.jdbc = jdbc;
    }
    @Scheduled(fixedDelayString = "${watcher.simulation.poll-delay:PT1S}")
    public void processReady() {
        Boolean enabled = jdbc.queryForObject("select simulation_enabled from telegram_admin_settings where singleton_id=1", Boolean.class);
        if (!Boolean.TRUE.equals(enabled)) return;
        for (Batch batch : debounce.claimReady(clock.instant())) simulations.evaluate(batch);
    }
}
