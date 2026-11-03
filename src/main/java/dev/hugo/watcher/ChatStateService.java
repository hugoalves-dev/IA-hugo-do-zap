package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;

/** Validates archive changes; PostgreSQL persistence and locking live in ChatSnapshotRepository. */
public class ChatStateService {
    public enum ApplyResult { APPLIED, STALE, QUARANTINED }

    private final ChatSnapshotRepository snapshots;
    private final TransactionTemplate transactions;
    private final IdentityService identities;
    private final ArchiveModeService archiveModes;

    public ChatStateService(ChatSnapshotRepository snapshots, PlatformTransactionManager manager,
                            IdentityService identities, ArchiveModeService archiveModes) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.transactions = new TransactionTemplate(manager);
        this.identities = Objects.requireNonNull(identities);
        this.archiveModes = Objects.requireNonNull(archiveModes);
    }

    /** Compatibility constructor for standalone PostgreSQL integration tests. */
    public ChatStateService(JdbcTemplate jdbc, PlatformTransactionManager manager, ArchiveModeService archiveModes) {
        this(new ChatSnapshotRepository(jdbc), manager, new IdentityService(jdbc, manager), archiveModes);
    }

    public ApplyResult apply(ChatUpdate update) {
        Objects.requireNonNull(update, "update");
        return Objects.requireNonNull(transactions.execute(status -> {
            Instant receivedAt = Instant.now();
            String jid = validJid(update.chatJid()) ? update.chatJid() : null;
            if (jid == null || !update.archiveSourceVerified() || update.archived().isEmpty()
                    || update.timestamp() == null || update.externalId().isEmpty()
                    || update.externalId().get().isBlank()) {
                snapshots.quarantine(update, "unverified_or_incomplete_state", receivedAt);
                return ApplyResult.QUARANTINED;
            }

            String canonical = snapshots.lockCurrentSnapshot(jid);
            var current = snapshots.snapshotForUpdate(canonical);
            if (current == null && !update.synchronizedSnapshot()) {
                snapshots.quarantine(update, "unsynchronized_bootstrap", receivedAt);
                return ApplyResult.QUARANTINED;
            }
            if (current != null && !current.synchronizedSnapshot() && !update.synchronizedSnapshot()) {
                snapshots.quarantine(update, "unsynchronized_snapshot", receivedAt);
                return ApplyResult.QUARANTINED;
            }
            if (current != null && !update.timestamp().isAfter(current.timestamp())) {
                snapshots.quarantine(update, "stale_or_unordered_state", receivedAt);
                return ApplyResult.STALE;
            }

            int changed = snapshots.upsert(update, canonical);
            if (changed == 0) {
                snapshots.quarantine(update, "stale_or_concurrent_state", receivedAt);
                return ApplyResult.STALE;
            }
            if (update.displayName().isPresent()) {
                String resolvedName = identities.observeContactName(
                        canonical, update.displayName().orElseThrow(), ContactNameSource.CHAT);
                snapshots.updateDisplayName(canonical, resolvedName.equals(canonical) ? null : resolvedName);
            }
            if (!archiveModes.getMode().accepts(update.archived().orElseThrow(), update.synchronizedSnapshot())) {
                snapshots.deletePending(canonical);
            }
            return ApplyResult.APPLIED;
        }), "Chat update transaction returned no result");
    }

    /** Clears pending work only for the legacy read signals: unread count zero or inbound READ (status 4). */
    public int clearRead(String chatJid, Instant eventTimestamp, java.util.Optional<Integer> unreadCount,
                         java.util.Optional<Boolean> fromMe, java.util.Optional<Integer> status) {
        if (!validJid(chatJid) || eventTimestamp == null) return 0;
        boolean read = unreadCount != null && unreadCount.filter(value -> value == 0).isPresent()
                || fromMe != null && fromMe.filter(value -> !value).isPresent()
                && status != null && status.filter(value -> value == 4).isPresent();
        if (!read) return 0;
        return Objects.requireNonNull(transactions.execute(tx -> {
            String canonical = snapshots.lockCurrentSnapshot(chatJid);
            var states = snapshots.readStateForUpdate(canonical);
            if (states.isEmpty() || states.getFirst().archived() == null
                    || !states.getFirst().synchronizedSnapshot()) return 0;
            Instant lastRead = states.getFirst().lastReadAt();
            if (lastRead != null && !eventTimestamp.isAfter(lastRead)) return 0;
            snapshots.updateLastRead(canonical, eventTimestamp);
            var pendingLast = snapshots.pendingLastForUpdate(canonical);
            if (pendingLast.isEmpty() || pendingLast.getFirst() == null) return 0;
            Instant latestMessage;
            try { latestMessage = Instant.parse(pendingLast.getFirst()); }
            catch (RuntimeException ex) { return 0; }
            if (latestMessage.isAfter(eventTimestamp)) return 0;
            return snapshots.deletePending(canonical);
        }));
    }

    private static boolean validJid(String jid) {
        return jid != null && !jid.isBlank() && jid.length() <= 255 && jid.contains("@");
    }
}
