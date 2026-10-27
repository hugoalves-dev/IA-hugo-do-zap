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
}
