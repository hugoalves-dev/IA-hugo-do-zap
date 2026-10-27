package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationOutboxRepository {
    private static final long MAX_BACKOFF_SECONDS = 15 * 60L;
    private static final long LEASE_SECONDS = 120L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public NotificationOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public void enqueue(String actionKey, String actionType, String payload, String pendingSnapshot,
                        Instant createdAt, Instant sensitiveExpiresAt) {
        jdbc.update("""
                insert into outbox_actions(action_key, action_type, payload, pending_snapshot, status,
                    attempts, available_at, created_at, updated_at, sensitive_expires_at)
                values (?, ?, ?::jsonb, ?::jsonb, 'PENDING', 0, ?, ?, ?, ?)
                on conflict (action_key) do nothing
                """, actionKey, actionType, payload, pendingSnapshot, Timestamp.from(createdAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(sensitiveExpiresAt));
    }

    public boolean hasActiveAction(String actionType, Instant now) {
        Integer count = jdbc.queryForObject("""
                select count(*) from outbox_actions
                where action_type=? and status in ('PENDING','PROCESSING') and sensitive_expires_at>?
                """, Integer.class, actionType, Timestamp.from(now));
        return count != null && count > 0;
    }

    public OutboxAction claimNext(Instant now) {
        return transactions.execute(status -> {
            List<OutboxAction> candidates = jdbc.query("""
                    select id,action_key,action_type,payload::text,pending_snapshot::text,attempts,created_at,sensitive_expires_at
                    from outbox_actions
                    where sensitive_expires_at > ? and available_at <= ?
                      and (status='PENDING' or (status='PROCESSING' and lease_expires_at <= ?))
                    order by available_at,created_at,id
                    for update skip locked
                    limit 1
                    """, (rs, row) -> new OutboxAction(rs.getLong("id"), rs.getString("action_key"),
                    rs.getString("action_type"), rs.getString("payload"), rs.getString("pending_snapshot"),
                    rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("sensitive_expires_at").toInstant(), null), Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now));
            if (candidates.isEmpty()) return null;

            OutboxAction candidate = candidates.getFirst();
            UUID token = UUID.randomUUID();
            int updated = jdbc.update("""
                    update outbox_actions
                    set status='PROCESSING', attempts=attempts+1, lease_token=?, lease_expires_at=?, updated_at=?
                    where id=?
                    """, token, Timestamp.from(now.plusSeconds(LEASE_SECONDS)), Timestamp.from(now), candidate.id());
            if (updated != 1) return null;
            return new OutboxAction(candidate.id(), candidate.actionKey(), candidate.actionType(), candidate.payload(),
                    candidate.pendingSnapshot(), candidate.attempts() + 1, candidate.createdAt(), candidate.sensitiveExpiresAt(), token.toString());
        });
    }

    public boolean acknowledge(OutboxAction action, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int updated = jdbc.update("""
                    update outbox_actions set status='ACKNOWLEDGED',payload='{}'::jsonb,pending_snapshot='[]'::jsonb,
                        sensitive_expires_at=?,lease_token=null,lease_expires_at=null,last_error=null,updated_at=?
                    where id=? and status='PROCESSING' and lease_token=?::uuid
                    """, Timestamp.from(now), Timestamp.from(now), action.id(), action.leaseToken());
            if (updated != 1) return false;
            if ("archived_messages".equals(action.actionType())) clearIfUnchanged(action.pendingSnapshot());
            return true;
        }));
    }

    /** Marks an action complete without deleting pending rows when current policy suppresses delivery. */
    public boolean suppress(OutboxAction action, Instant now) {
        return jdbc.update("""
                update outbox_actions set status='ACKNOWLEDGED',payload='{}'::jsonb,pending_snapshot='[]'::jsonb,
                    sensitive_expires_at=?,lease_token=null,lease_expires_at=null,last_error=null,updated_at=?
                where id=? and status='PROCESSING' and lease_token=?::uuid
                """, Timestamp.from(now), Timestamp.from(now), action.id(), action.leaseToken()) == 1;
    }

    /** Cancels unclaimed connection alerts on reconnect, leaving every other action untouched. */
    public int cancelPendingConnectionAlerts(Instant now) {
        return jdbc.update("""
                update outbox_actions set status='CANCELLED',payload='{}'::jsonb,pending_snapshot='[]'::jsonb,
                    sensitive_expires_at=?,lease_token=null,lease_expires_at=null,last_error='ConnectionRecovered',updated_at=?
                where action_type='whatsapp_connection_lost' and status='PENDING'
                """, Timestamp.from(now), Timestamp.from(now));
    }

    /** Cancels only the currently leased connection alert; a stale worker cannot change a newer claim. */
    public boolean cancelClaimedConnectionAlert(OutboxAction action, Instant now) {
        if (action == null || !"whatsapp_connection_lost".equals(action.actionType()) || action.leaseToken() == null) return false;
        return jdbc.update("""
                update outbox_actions set status='CANCELLED',payload='{}'::jsonb,pending_snapshot='[]'::jsonb,
                    sensitive_expires_at=?,lease_token=null,lease_expires_at=null,last_error='ConnectionRecovered',updated_at=?
                where id=? and action_type='whatsapp_connection_lost' and status='PROCESSING' and lease_token=?::uuid
                """, Timestamp.from(now), Timestamp.from(now), action.id(), action.leaseToken()) == 1;
    }

    public void retry(OutboxAction action, Instant now, String errorType) {
        int exponent = Math.min(30, Math.max(0, action.attempts() - 1));
        long backoff = Math.min(MAX_BACKOFF_SECONDS, 10L << exponent);
        jdbc.update("""
                update outbox_actions set status='PENDING',lease_token=null,lease_expires_at=null,
                    available_at=?,last_error=?,updated_at=?
                where id=? and status='PROCESSING' and lease_token=?::uuid
                """, Timestamp.from(now.plusSeconds(backoff)), sanitizeError(errorType), Timestamp.from(now), action.id(), action.leaseToken());
    }

    private void clearIfUnchanged(String snapshotJson) {
        List<PendingSnapshot> snapshots = jdbc.query("""
                select chat_jid,message_count,first_message_at,last_message_at,updated_at
                from jsonb_to_recordset(?::jsonb) as snapshot(
                    chat_jid text,message_count integer,first_message_at text,last_message_at text,updated_at timestamptz)
                """, (rs, row) -> new PendingSnapshot(rs.getString("chat_jid"), rs.getInt("message_count"),
                rs.getString("first_message_at"), rs.getString("last_message_at"),
                rs.getTimestamp("updated_at").toInstant()), snapshotJson);
        for (PendingSnapshot snapshot : snapshots) {
            jdbc.update("""
                    delete from pending_notification p using chat_snapshot c
                    where p.chat_jid=? and c.chat_jid=p.chat_jid and c.archived is true
                      and p.payload->>'messageCount'=? and p.payload->>'firstMessageAt'=?
                      and p.payload->>'lastMessageAt'=? and p.updated_at=?
                    """, snapshot.chatJid(), Integer.toString(snapshot.messageCount()), snapshot.firstMessageAt(),
                    snapshot.lastMessageAt(), Timestamp.from(snapshot.updatedAt()));
        }
    }

    private static String sanitizeError(String errorType) {
        if (errorType == null || !errorType.matches("[A-Za-z0-9_.-]{1,100}")) return "DeliveryFailure";
        return errorType;
    }

    private record PendingSnapshot(String chatJid, int messageCount, String firstMessageAt,
                                   String lastMessageAt, Instant updatedAt) { }
}
