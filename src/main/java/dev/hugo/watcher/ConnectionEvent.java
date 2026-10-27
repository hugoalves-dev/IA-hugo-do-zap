package dev.hugo.watcher;

import java.time.Instant;

/** A normalized connection state supplied by a verified upstream adapter. */
public record ConnectionEvent(State state, Instant occurredAt, String reason, Integer statusCode) {
    public enum State { CONNECTED, DISCONNECTED, DEVICE_REMOVED, MANUAL_PAIRING_REQUIRED }
}
