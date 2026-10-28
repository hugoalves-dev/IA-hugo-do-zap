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
            Boolean enabled = jdbc.queryForObject("select simulation_enabled from telegram_admin_settings where singleton_id=1", Boolean.class);
            if (!Boolean.TRUE.equals(enabled)) return;
        }
        for (Batch batch : debounce.claimReady(clock.instant())) simulations.evaluate(batch);
    }
}
