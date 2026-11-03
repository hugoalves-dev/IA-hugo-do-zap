package dev.hugo.watcher;

public final class NotificationRetryException extends RuntimeException {
    private final long delayMillis;

    public NotificationRetryException(String message, long delayMillis) {
        super(message);
        if (delayMillis < 1) throw new IllegalArgumentException("retry delay must be positive");
        this.delayMillis = delayMillis;
    }

    public long delayMillis() { return delayMillis; }
}
