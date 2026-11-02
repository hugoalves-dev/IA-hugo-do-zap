package dev.hugo.watcher;

import dev.hugo.watcher.ai.AiClassificationProperties;
import dev.hugo.watcher.simulation.WatcherSimulationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OperationalStateTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AutomationProperties automation = new AutomationProperties();
    private final WatcherSimulationProperties simulation = new WatcherSimulationProperties();
    private final AiClassificationProperties ai = new AiClassificationProperties(
            true, null, null, "test-router", "test-model", 10, Duration.ofSeconds(1),
            null, true, false, true, false);

    private OperationalState state(boolean realMode, boolean emergency, boolean paused) {
        when(jdbc.queryForObject(contains("emergency_latched"), eq(Boolean.class))).thenReturn(emergency);
        when(jdbc.queryForObject(contains("simulation_paused"), eq(Boolean.class))).thenReturn(paused);
        return new OperationalState(jdbc, automation, ai, simulation, realMode);
    }

    @Test void shadowCanClassifyWithRealKillSwitchButCannotEnableRealActions() {
        automation.setKillSwitchEnabled(true);
        automation.setIntelligentAlertsEnabled(true);
        automation.setAutoReplyEnabled(true);

        OperationalState operational = state(false, false, false);
        assertTrue(operational.simulation());
        assertTrue(operational.classification());
        assertTrue(operational.simulatedAlerts());
        assertTrue(operational.simulatedReplies());
        assertTrue(operational.killSwitch());
        assertFalse(operational.alerts());
        assertFalse(operational.replies());
        assertFalse(operational.realModeEnabled());
    }

    @Test void emergencyStillStopsClassificationAndAllSimulatedActions() {
        automation.setIntelligentAlertsEnabled(true);
        automation.setAutoReplyEnabled(true);

        OperationalState operational = state(true, true, false);
        assertFalse(operational.classification());
        assertFalse(operational.simulatedAlerts());
        assertFalse(operational.simulatedReplies());
        assertFalse(operational.alerts());
        assertFalse(operational.replies());
    }

    @Test void pausedShadowCannotBypassRealKillSwitch() {
        automation.setKillSwitchEnabled(true);
        OperationalState operational = state(true, false, true);
        assertFalse(operational.simulation());
        assertFalse(operational.classification());
    }

    @Test void realModeCanStillClassifyWhenUnblockedAndShadowPaused() {
        automation.setKillSwitchEnabled(false);
        OperationalState operational = state(true, false, true);
        assertTrue(operational.classification());
    }
}
