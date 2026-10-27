package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Removes message snippets and quarantine identifiers at their bounded retention deadline. */
@Service
public class SensitiveDataRetentionService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public SensitiveDataRetentionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelay = 60_000L)
    public void purgeExpired() { purge(Instant.now()); }

    public int purge(Instant now) {
        Timestamp cutoff = Timestamp.from(now);
        int pending = jdbc.update("delete from pending_notification where expires_at <= ?", cutoff);
        int scrubbedPreviews = scrubExpiredPreviews(now);
        int quarantined = jdbc.update("delete from quarantined_events where sensitive_expires_at <= ?", cutoff);
        int deduplication = jdbc.update("delete from processed_message where processed_at <= ?",
                Timestamp.from(now.minusSeconds(7 * 86_400L)));
        return pending + scrubbedPreviews + quarantined + deduplication;
    }

    private int scrubExpiredPreviews(Instant now) {
        List<PendingPreview> rows = jdbc.query("""
                select chat_jid,payload::text as payload,expires_at
                from pending_notification where expires_at > ? and jsonb_exists(payload, 'preview')
                """, (rs, row) -> new PendingPreview(rs.getString("chat_jid"), rs.getString("payload"),
                rs.getTimestamp("expires_at").toInstant()), Timestamp.from(now));
        int scrubbed = 0;
        for (PendingPreview row : rows) {
            Instant previewExpiresAt = previewExpiresAt(row.payload(), row.expiresAt());
            if (!previewExpiresAt.isAfter(now)) {
                scrubbed += jdbc.update("update pending_notification set payload=payload-'preview' where chat_jid=? and expires_at>? and jsonb_exists(payload, 'preview')",
                        row.chatJid(), Timestamp.from(now));
            }
        }
        return scrubbed;
    }

    private static Instant previewExpiresAt(String payload, Instant fallback) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = JSON.readValue(payload, Map.class);
            Object value = fields.get("previewExpiresAt");
            if (value instanceof String timestamp) return Instant.parse(timestamp);
        } catch (RuntimeException ignored) {
            // An unreadable preview expiry is treated as expired.
            return Instant.MIN;
        }
        return fallback;
    }

    private record PendingPreview(String chatJid, String payload, Instant expiresAt) { }
}
