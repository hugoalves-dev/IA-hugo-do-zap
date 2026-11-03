package dev.hugo.watcher;

public final class NotificationRetryException extends RuntimeException {
    private final long delayMillis;
    private final boolean rateLimited;

    public NotificationRetryException(String message, long delayMillis) {
        this(message, delayMillis, false);
    }

    public NotificationRetryException(String message, long delayMillis, boolean rateLimited) {
        super(message);
        if (delayMillis < 1) throw new IllegalArgumentException("retry delay must be positive");
        this.delayMillis = delayMillis;
        this.rateLimited = rateLimited;
    }

    public long delayMillis() { return delayMillis; }
    public boolean rateLimited() { return rateLimited; }
}
