package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/** Removes message snippets and quarantine identifiers at their bounded retention deadline. */
@Service
public class SensitiveDataRetentionService {
    private final JdbcTemplate jdbc;

    public SensitiveDataRetentionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelay = 60_000L)
    public void purgeExpired() { purge(Instant.now()); }

    public int purge(Instant now) {
        Timestamp cutoff = Timestamp.from(now);
        int pending = jdbc.update("delete from pending_notification where expires_at <= ?", cutoff);
        int quarantined = jdbc.update("delete from quarantined_events where sensitive_expires_at <= ?", cutoff);
        int deduplication = jdbc.update("delete from processed_message where processed_at <= ?",
                Timestamp.from(now.minusSeconds(7 * 86_400L)));
        return pending + quarantined + deduplication;
    }
}
