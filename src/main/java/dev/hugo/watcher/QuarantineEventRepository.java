package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Redacted quarantine persistence. Never stores source message/chat payloads. */
@Repository
public class QuarantineEventRepository {
    private final JdbcTemplate jdbc;

    public QuarantineEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(String kind, String sourceEventId, String reason, Instant now) {
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
