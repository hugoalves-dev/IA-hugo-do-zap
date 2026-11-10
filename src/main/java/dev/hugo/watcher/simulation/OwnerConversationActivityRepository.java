package dev.hugo.watcher.simulation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Reads provenance captured only from authenticated Evolution events with fromMe=true. */
@Repository
public class OwnerConversationActivityRepository {
    private final JdbcTemplate jdbc;

    public OwnerConversationActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OwnerConversationActivity activity(String canonicalChat, Instant now, Duration activeWindow) {
        if (canonicalChat == null || canonicalChat.isBlank() || now == null
                || activeWindow == null || activeWindow.isNegative() || activeWindow.isZero())
            return OwnerConversationActivity.UNKNOWN;
        Instant cutoff = now.minus(activeWindow);
        List<Instant> latest = jdbc.query("""
                select max(r.observed_at) as latest
                  from whatsapp_owner_message_references r
                 where (r.chat_jid=? or r.chat_jid in
                       (select alias from identity_aliases where canonical_identity=?))
                   and r.expires_at>?
                """, (rs, row) -> {
                    Timestamp value = rs.getTimestamp("latest");
                    return value == null ? null : value.toInstant();
                }, canonicalChat, canonicalChat, Timestamp.from(now));
        Instant last = latest.isEmpty() ? null : latest.getFirst();
        return last == null || !last.isAfter(cutoff)
                ? OwnerConversationActivity.INACTIVE : OwnerConversationActivity.ACTIVE;
    }
}
