package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;

/** Compatibility bridge while all callers migrate to the managed quarantine repository. */
final class QuarantineWriter {
    private QuarantineWriter() { }

    static void write(JdbcTemplate jdbc, String kind, String sourceEventId, String reason, Instant now) {
        new QuarantineEventRepository(jdbc).write(kind, sourceEventId, reason, now);
    }
}
