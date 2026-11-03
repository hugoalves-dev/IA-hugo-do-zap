package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * PostgreSQL-native snapshot/notification persistence. Advisory locks, conditional
 * upsert and read acknowledgements must share ChatStateService's transaction.
 */
@Repository
public class ChatSnapshotRepository {
    private final JdbcTemplate jdbc;

    public ChatSnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String lockCurrentSnapshot(String jid) {
        String canonical = canonicalFor(jid);
        while (true) {
            lockSnapshot(canonical);
            String currentCanonical = canonicalFor(jid);
            if (canonical.equals(currentCanonical)) return canonical;
            canonical = currentCanonical;
        }
    }

    private String canonicalFor(String jid) {
        List<String> rows = jdbc.query("select canonical_identity from identity_aliases where alias=?",
                (rs, row) -> rs.getString(1), jid);
        return rows.isEmpty() ? jid : rows.getFirst();
    }

    private void lockSnapshot(String jid) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, jid);
    }

    public Snapshot snapshotForUpdate(String jid) {
        List<Snapshot> rows = jdbc.query("""
                select archived,snapshot_at,synchronized_snapshot
                  from chat_snapshot where chat_jid=? for update
                """, (rs, row) -> new Snapshot((Boolean) rs.getObject("archived"),
                rs.getTimestamp("snapshot_at").toInstant(),
                rs.getBoolean("synchronized_snapshot")), jid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Ignore stale arrivals even when competing writers race after acquiring locks. */
    public int upsert(ChatUpdate update, String canonical) {
        return jdbc.update("""
                insert into chat_snapshot(chat_jid, archived, snapshot_at, display_name, is_group, synchronized_snapshot)
                values (?, ?, ?, ?, ?, ?)
                on conflict (chat_jid) do update set
                    archived=excluded.archived,
                    snapshot_at=excluded.snapshot_at,
                    display_name=coalesce(excluded.display_name, chat_snapshot.display_name),
                    is_group=excluded.is_group,
                    synchronized_snapshot=chat_snapshot.synchronized_snapshot or excluded.synchronized_snapshot
                where chat_snapshot.snapshot_at < excluded.snapshot_at
                  and (chat_snapshot.synchronized_snapshot or excluded.synchronized_snapshot)
                """, canonical, update.archived().orElseThrow(), Timestamp.from(update.timestamp()),
                update.displayName().orElse(null), update.group(), update.synchronizedSnapshot());
    }

    public void updateDisplayName(String canonical, String displayName) {
        jdbc.update("update chat_snapshot set display_name=? where chat_jid=?", displayName, canonical);
    }

    public int deletePending(String canonical) {
        return jdbc.update("delete from pending_notification where chat_jid = ?", canonical);
    }

    public List<ReadState> readStateForUpdate(String canonical) {
        return jdbc.query("""
                select archived,synchronized_snapshot,last_read_at from chat_snapshot where chat_jid=? for update
                """, (rs, row) -> new ReadState((Boolean) rs.getObject("archived"),
                rs.getBoolean("synchronized_snapshot"),
                rs.getTimestamp("last_read_at") == null ? null : rs.getTimestamp("last_read_at").toInstant()), canonical);
    }

    public void updateLastRead(String canonical, Instant at) {
        jdbc.update("update chat_snapshot set last_read_at=? where chat_jid=?", Timestamp.from(at), canonical);
    }

    public List<String> pendingLastForUpdate(String canonical) {
        return jdbc.query("select payload->>'lastMessageAt' from pending_notification where chat_jid=? for update",
                (rs, row) -> rs.getString(1), canonical);
    }

    public void quarantine(ChatUpdate update, String reason, Instant now) {
        QuarantineWriter.write(jdbc, "CHAT_STATE", update.externalId().orElse(null), reason, now);
    }

    public record Snapshot(Boolean archived, Instant timestamp, boolean synchronizedSnapshot) { }
    public record ReadState(Boolean archived, boolean synchronizedSnapshot, Instant lastReadAt) { }
}
