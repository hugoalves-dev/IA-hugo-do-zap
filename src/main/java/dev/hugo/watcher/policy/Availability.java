package dev.hugo.watcher.policy;

import java.util.Objects;

public record Availability(AvailabilityState state, AvailabilitySource source, String reason) {
    public Availability {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reason, "reason");
    }
}
