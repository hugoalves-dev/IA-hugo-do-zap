package dev.hugo.watcher.policy;

import java.time.Instant;
import java.util.Objects;

public record TemporaryAvailabilityOverride(AvailabilityState state, Instant startsAt, Instant expiresAt) {
    public TemporaryAvailabilityOverride {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!startsAt.isBefore(expiresAt)) throw new IllegalArgumentException("expiresAt must follow startsAt");
    }

    public boolean appliesAt(Instant instant) {
        return !instant.isBefore(startsAt) && instant.isBefore(expiresAt);
    }
}
