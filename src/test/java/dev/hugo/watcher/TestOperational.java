package dev.hugo.watcher;

import dev.hugo.watcher.ai.AiClassificationProperties;
import dev.hugo.watcher.notification.DecisionRuntimeGate;
import dev.hugo.watcher.simulation.WatcherSimulationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.time.Duration;

public final class TestOperational {
    public static OperationalState state(JdbcTemplate jdbc) {
        var flags=new AutomationProperties(); flags.setKillSwitchEnabled(false); flags.setAutoReplyEnabled(true); flags.setIntelligentAlertsEnabled(true);
        var ai=new AiClassificationProperties(true,null,null,"key","model",10,Duration.ofSeconds(1),null,true,false,true,false);
        return new OperationalState(jdbc,flags,ai,new WatcherSimulationProperties(),true);
    }
    public static DecisionRuntimeGate gate(JdbcTemplate jdbc, Clock clock, String owner) {
        var gate=new DecisionRuntimeGate(jdbc,clock,new WatcherProperties(),owner);
        gate.configureOperationalState(state(jdbc)); return gate;
    }
}
