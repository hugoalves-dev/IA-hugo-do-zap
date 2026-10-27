package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class ChatStateService {
    public enum ApplyResult { APPLIED, STALE, QUARANTINED }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ChatStateService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public ApplyResult apply(ChatUpdate update) {
        Objects.requireNonNull(update, "update");
        return Objects.requireNonNull(transactions.execute(status -> {
            Instant receivedAt = Instant.now();
            String jid = validJid(update.chatJid()) ? update.chatJid() : null;
            if (jid == null || !update.archiveSourceVerified() || update.archived().isEmpty()
                    || update.timestamp() == null || update.externalId().isEmpty()
                    || update.externalId().get().isBlank()) {
                quarantine(update, "unverified_or_incomplete_state", receivedAt);
                return ApplyResult.QUARANTINED;
            }

            String canonical = canonicalFor(jid);
            Snapshot current = snapshotForUpdate(canonical);
            if (current == null && !update.synchronizedSnapshot()) {
                quarantine(update, "unsynchronized_bootstrap", receivedAt);
                return ApplyResult.QUARANTINED;
            }
            if (current != null && !current.synchronizedSnapshot() && !update.synchronizedSnapshot()) {
                quarantine(update, "unsynchronized_snapshot", receivedAt);
                return ApplyResult.QUARANTINED;
            }
            if (current != null && !update.timestamp().isAfter(current.timestamp())) {
                quarantine(update, "stale_or_unordered_state", receivedAt);
                return ApplyResult.STALE;
            }

            jdbc.update("""
                    insert into chat_snapshot(chat_jid, archived, snapshot_at, display_name, is_group, synchronized_snapshot)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (chat_jid) do update set
                        archived=excluded.archived,
                        snapshot_at=excluded.snapshot_at,
                        display_name=coalesce(excluded.display_name, chat_snapshot.display_name),
                        is_group=excluded.is_group,
                        synchronized_snapshot=chat_snapshot.synchronized_snapshot or excluded.synchronized_snapshot
                    """, canonical, update.archived().orElseThrow(), Timestamp.from(update.timestamp()),
                    update.displayName().orElse(null), update.group(), update.synchronizedSnapshot());
            if (!update.archived().orElseThrow()) {
                jdbc.update("delete from pending_notification where chat_jid = ?", canonical);
            }
            return ApplyResult.APPLIED;
        }), "Chat update transaction returned no result");
    }

    /** Clears pending work only for the legacy read signals: unread count zero or inbound READ (status 4). */
    public int clearRead(String chatJid, java.util.Optional<Integer> unreadCount,
                         java.util.Optional<Boolean> fromMe, java.util.Optional<Integer> status) {
        if (!validJid(chatJid)) return 0;
        boolean read = unreadCount != null && unreadCount.filter(value -> value == 0).isPresent()
                || fromMe != null && fromMe.filter(value -> !value).isPresent()
                && status != null && status.filter(value -> value == 4).isPresent();
        if (!read) return 0;
        return Objects.requireNonNull(transactions.execute(tx -> {
            return jdbc.update("delete from pending_notification where chat_jid = ?", canonicalFor(chatJid));
        }));
    }

    private Snapshot snapshotForUpdate(String jid) {
        List<Snapshot> rows = jdbc.query("select archived, snapshot_at, synchronized_snapshot from chat_snapshot where chat_jid=? for update",
                (rs, row) -> new Snapshot((Boolean) rs.getObject("archived"), rs.getTimestamp("snapshot_at").toInstant(),
                        rs.getBoolean("synchronized_snapshot")), jid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String canonicalFor(String jid) {
        List<String> rows = jdbc.query("select canonical_identity from identity_aliases where alias=?",
                (rs, row) -> rs.getString(1), jid);
        return rows.isEmpty() ? jid : rows.getFirst();
    }

    private void quarantine(ChatUpdate update, String reason, Instant now) {
        QuarantineWriter.write(jdbc, "CHAT_STATE", update.externalId().orElse(null), reason, now);
    }

    private static boolean validJid(String jid) {
        return jid != null && !jid.isBlank() && jid.length() <= 255 && jid.contains("@");
    }

    private record Snapshot(Boolean archived, Instant timestamp, boolean synchronizedSnapshot) { }
}
