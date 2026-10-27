package dev.hugo.watcher.policy;

import java.time.LocalTime;
import java.util.Objects;

public record WorkWindow(LocalTime start, LocalTime end) {
    public WorkWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) throw new IllegalArgumentException("work window start must be before end");
    }

    public boolean contains(LocalTime time) {
        return !time.isBefore(start) && time.isBefore(end);
    }
}
