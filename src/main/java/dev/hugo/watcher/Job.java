package dev.hugo.watcher;

import java.util.UUID;

public record Job(UUID id, long inboxEventId, UUID leaseToken, int attempts) { }
