package dev.hugo.watcher;

import dev.hugo.watcher.ai.AiClassificationProperties;
import dev.hugo.watcher.simulation.WatcherSimulationProperties;
import org.springframework.jdbc.core.JdbcTemplate;

/** Restrictive database latches are deliberately separate from declarative settings. */
public class OperationalState {
    private final OperationalRestrictionDataRepository restrictions;
    private final OperationalRestrictionJdbcRepository legacy;
    private final AutomationProperties automation;
    private final AiClassificationProperties ai;
    private final WatcherSimulationProperties simulation;
    private final boolean realMode;

    public OperationalState(OperationalRestrictionDataRepository restrictions, AutomationProperties automation,
                            AiClassificationProperties ai, WatcherSimulationProperties simulation,
                            boolean realMode) {
        this.restrictions = restrictions;
        this.legacy = null;
        this.automation = automation;
        this.ai = ai;
        this.simulation = simulation;
        this.realMode = realMode;
    }

    /** Backward-compatible explicit constructor for unit tests. */
    public OperationalState(JdbcTemplate jdbc, AutomationProperties automation, AiClassificationProperties ai,
                            WatcherSimulationProperties simulation, boolean realMode) {
        this.restrictions = null;
        this.legacy = new OperationalRestrictionJdbcRepository(jdbc);
        this.automation = automation;
        this.ai = ai;
        this.simulation = simulation;
        this.realMode = realMode;
    }

    public boolean requireChatConsent() { return ai.requireChatConsent(); }

    public boolean emergency() {
        Boolean latched = restrictions == null ? legacy.emergencyLatched()
                : restrictions.findById(1).map(OperationalRestrictionRow::emergencyLatched).orElse(null);
        return !Boolean.FALSE.equals(latched);
    }

    public boolean simulationPaused() {
        Boolean paused = restrictions == null ? legacy.simulationPaused()
                : restrictions.findById(1).map(OperationalRestrictionRow::simulationPaused).orElse(null);
        return !Boolean.FALSE.equals(paused);
    }

    public boolean killSwitch() { return automation.isKillSwitchEnabled() || emergency(); }
    /** The real-action kill switch does not prevent an explicitly enabled, consented Shadow Mode. */
    public boolean classification() {
        return ai.enabled() && ai.classificationEnabled() && !emergency()
                && (simulation() || (realMode && !automation.isKillSwitchEnabled()));
    }
    /** These flags describe simulated eligibility, never permission to send real actions. */
    public boolean simulatedAlerts() { return automation.isIntelligentAlertsEnabled() && !emergency(); }
    public boolean simulatedReplies() { return automation.isAutoReplyEnabled() && !emergency(); }
    public boolean classificationConfigured() { return ai.configured(); }
    public boolean realModeEnabled() { return realMode; }
    public boolean alerts() { return automation.isIntelligentAlertsEnabled() && !killSwitch(); }
    public boolean replies() { return automation.isAutoReplyEnabled() && !killSwitch(); }
    public boolean simulation() { return simulation.isEnabled() && !simulationPaused(); }
}
