package dev.hugo.watcher;

import java.time.Instant;

/** A persisted webhook request. Delivery is at least once across ambiguous HTTP failures. */
public record OutboxAction(long id, String actionKey, String actionType, String payload,
                           String pendingSnapshot, int attempts, Instant createdAt,
                           Instant sensitiveExpiresAt, String leaseToken) { }
