package dev.hugo.watcher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobRepository {
    List<Job> claim(int limit, Instant now, Duration leaseDuration);
    void complete(UUID jobId, UUID leaseToken);
    boolean expire(UUID jobId, UUID leaseToken);
    boolean retry(UUID jobId, UUID leaseToken, Instant now, Instant availableAt);

    /** RabbitMQ owns retry delays; pending database state becomes immediately replayable. */
    default void makePendingImmediatelyAvailable(Instant now) { }

    default void preparePendingForRabbitStartup(Instant now) { }
    default List<RabbitPendingEvent> pendingForRabbit(int limit) { return List.of(); }
    default void markRabbitBacklogConfirmed(UUID jobId, Instant confirmedAt) { }
    default boolean isPending(String eventType, String externalId) { return false; }

    record RabbitPendingEvent(UUID jobId, String messageId, String payload, Instant receivedAt) { }
}
