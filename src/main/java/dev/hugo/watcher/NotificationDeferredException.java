package dev.hugo.watcher;

public final class NotificationDeferredException extends RuntimeException {
    private final long delayMillis;

    public NotificationDeferredException(long delayMillis) {
        super("notification is deferred by the active work-window policy");
        if (delayMillis < 1) throw new IllegalArgumentException("defer delay must be positive");
        this.delayMillis = delayMillis;
    }

    public long delayMillis() { return delayMillis; }
}
