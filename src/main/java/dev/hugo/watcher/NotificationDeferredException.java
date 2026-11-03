package dev.hugo.watcher;

public final class NotificationDeferredException extends RuntimeException {
    public enum Reason { WORK_WINDOW, RATE_LIMIT, ACTIVE_LEASE }

    private final long delayMillis;
    private final Reason reason;

    public NotificationDeferredException(long delayMillis) {
        this(delayMillis, Reason.WORK_WINDOW);
    }

    public NotificationDeferredException(long delayMillis, Reason reason) {
        super("notification is deferred until its delivery condition is eligible");
        if (delayMillis < 1) throw new IllegalArgumentException("defer delay must be positive");
        this.delayMillis = delayMillis;
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public long delayMillis() { return delayMillis; }
    public Reason reason() { return reason; }
}
