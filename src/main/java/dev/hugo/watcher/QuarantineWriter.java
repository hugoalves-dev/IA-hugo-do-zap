package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Keeps a redacted rejection record; raw message/chat payloads are never copied to quarantine. */
final class QuarantineWriter {
    private QuarantineWriter() { }

    static void write(JdbcTemplate jdbc, String kind, String sourceEventId, String reason, Instant now) {
        Instant receivedAt = now == null ? Instant.now() : now;
        jdbc.update("""
                insert into quarantined_events(event_kind, source_event_id, reason, payload, received_at, sensitive_expires_at)
                values (?, ?, ?, '{}'::jsonb, ?, ?)
                """, kind, normalizeId(sourceEventId), reason,
                Timestamp.from(receivedAt), Timestamp.from(receivedAt.plus(7, ChronoUnit.DAYS)));
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank() || id.length() > 255) return null;
        return id;
    }
}
