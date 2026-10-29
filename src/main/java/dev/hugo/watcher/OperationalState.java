package dev.hugo.watcher;

import dev.hugo.watcher.ai.AiClassificationProperties;
import dev.hugo.watcher.simulation.WatcherSimulationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Restrictive database latches are deliberately separate from declarative settings. */
@Component
public class OperationalState {
    private final JdbcTemplate jdbc;
    private final AutomationProperties automation;
    private final AiClassificationProperties ai;
    private final WatcherSimulationProperties simulation;
    private final boolean realMode;
    public OperationalState(JdbcTemplate jdbc, AutomationProperties automation, AiClassificationProperties ai,
                            WatcherSimulationProperties simulation,
                            @org.springframework.beans.factory.annotation.Value("${watcher.decision-producer.real-mode-enabled:false}") boolean realMode) {
        this.jdbc = jdbc; this.automation = automation; this.ai = ai; this.simulation = simulation; this.realMode = realMode;
    }
    public boolean requireChatConsent() { return ai.requireChatConsent(); }
    public boolean emergency() {
        return !Boolean.FALSE.equals(jdbc.queryForObject("select emergency_latched from operational_restrictions where singleton_id=1", Boolean.class));
    }
    public boolean simulationPaused() {
        return !Boolean.FALSE.equals(jdbc.queryForObject("select simulation_paused from operational_restrictions where singleton_id=1", Boolean.class));
    }
    public boolean killSwitch() { return automation.isKillSwitchEnabled() || emergency(); }
    public boolean classification() { return ai.enabled() && ai.classificationEnabled() && !killSwitch() && (realMode || simulation()); }
    public boolean alerts() { return automation.isIntelligentAlertsEnabled() && !killSwitch(); }
    public boolean replies() { return automation.isAutoReplyEnabled() && !killSwitch(); }
    public boolean simulation() { return simulation.isEnabled() && !simulationPaused(); }
}
