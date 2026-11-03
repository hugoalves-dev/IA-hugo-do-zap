package dev.hugo.watcher;

import java.time.Instant;

public final class NotificationDeferredException extends RuntimeException {
    public enum Reason { WORK_WINDOW, SCHEDULED, RATE_LIMIT, ACTIVE_LEASE }

    private final long delayMillis;
    private final Reason reason;
    private final Instant availableAt;

    public NotificationDeferredException(long delayMillis) {
        this(delayMillis, Reason.WORK_WINDOW, null);
    }

    public NotificationDeferredException(long delayMillis, Reason reason) {
        this(delayMillis, reason, null);
    }

    public NotificationDeferredException(long delayMillis, Reason reason, Instant availableAt) {
        super("notification is deferred until its delivery condition is eligible");
        if (delayMillis < 1) throw new IllegalArgumentException("defer delay must be positive");
        this.delayMillis = delayMillis;
        this.reason = java.util.Objects.requireNonNull(reason);
        this.availableAt = availableAt;
    }

    public long delayMillis() { return delayMillis; }
    public Reason reason() { return reason; }
    public Instant availableAt() { return availableAt; }
}
