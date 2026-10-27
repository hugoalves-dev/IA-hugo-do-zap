package dev.hugo.watcher.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record DecisionPolicy(boolean aiClassificationEnabled, boolean intelligentAlertsEnabled,
                             boolean autoReplyEnabled, boolean killSwitchEnabled,
                             Set<Situation> authorizedSituations, Map<Situation, ResponseTemplate> templates,
                             Duration simulationCooldown, Duration realCooldown,
                             Instant lastSimulationActionAt, Instant lastRealActionAt) {
    public DecisionPolicy {
        authorizedSituations = authorizedSituations == null ? Set.of() : Set.copyOf(authorizedSituations);
        templates = templates == null ? Map.of() : Map.copyOf(templates);
        simulationCooldown = simulationCooldown == null ? Duration.ofMinutes(5) : simulationCooldown;
        realCooldown = realCooldown == null ? Duration.ofMinutes(60) : realCooldown;
        if (simulationCooldown.isNegative() || realCooldown.isNegative()) throw new IllegalArgumentException("cooldowns cannot be negative");
    }
    public static DecisionPolicy disabled() {
        return new DecisionPolicy(false, false, false, true, Set.of(), Map.of(),
                Duration.ofMinutes(5), Duration.ofMinutes(60), null, null);
    }
}
