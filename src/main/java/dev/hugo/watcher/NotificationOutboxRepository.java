package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationOutboxRepository {
    private static final long MAX_BACKOFF_SECONDS = 15 * 60L;
    private static final long LEASE_SECONDS = 120L;
    private static final Duration DEFAULT_TELEGRAM_SEND_INTERVAL = Duration.ofMillis(1100);
    private static final Duration RETRY_AFTER_MARGIN = Duration.ofMillis(250);
    private static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Duration telegramSendInterval;
    private final dev.hugo.watcher.notification.DecisionDeliveryCoordinator deliveries;

    @Autowired
    public NotificationOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, DEFAULT_TELEGRAM_SEND_INTERVAL);
    }

    NotificationOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, Duration telegramSendInterval) {
        if (telegramSendInterval.isNegative()) throw new IllegalArgumentException("telegram send interval cannot be negative");
        this.jdbc = jdbc;
        this.deliveries = new dev.hugo.watcher.notification.DecisionDeliveryCoordinator(jdbc,manager);
        this.transactions = new TransactionTemplate(manager);
        this.telegramSendInterval = telegramSendInterval;
    }

    public void enqueue(String actionKey, String actionType, String payload, String pendingSnapshot,
                        Instant createdAt, Instant sensitiveExpiresAt) {
        jdbc.update("""
                insert into outbox_actions(action_key, action_type, payload, pending_snapshot, status,
                    attempts, available_at, created_at, updated_at, sensitive_expires_at)
                values (?, ?, ?::jsonb, ?::jsonb, 'PENDING', 0, ?, ?, ?,
                    least(?::timestamptz, clock_timestamp() + interval '7 days', ?::timestamptz + interval '7 days'))
                on conflict (action_key) do update set
                    payload=excluded.payload,
                    pending_snapshot=excluded.pending_snapshot,
                    status='PENDING',
                    attempts=0,
                    available_at=excluded.available_at,
                    created_at=excluded.created_at,
                    updated_at=excluded.updated_at,
                    sensitive_expires_at=excluded.sensitive_expires_at,
                    last_error=null,
                    lease_token=null,
                    lease_expires_at=null
                where outbox_actions.status='ACKNOWLEDGED'
                """, actionKey, actionType, payload, pendingSnapshot, Timestamp.from(createdAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(sensitiveExpiresAt),
                Timestamp.from(createdAt));
    }

    public boolean hasActiveAction(String actionType, Instant now) {
        Integer count = jdbc.queryForObject("""
                select count(*) from outbox_actions
                where action_type=? and status in ('PENDING','PROCESSING') and sensitive_expires_at>clock_timestamp()
                """, Integer.class, actionType);
        return count != null && count > 0;
    }

    public OutboxAction claimNext(Instant now) {
        return transactions.execute(status -> {
            Timestamp nextAllowedAt = lockTelegramRateLimitRow();
            RateLimitState rateLimit = new RateLimitState(nextAllowedAt,
                    jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant());
            if (rateLimit.nextAllowedAt() != null
                    && rateLimit.nextAllowedAt().toInstant().isAfter(rateLimit.databaseNow())) return null;

            List<OutboxAction> candidates = jdbc.query("""
                    select id,action_key,action_type,payload::text,pending_snapshot::text,attempts,created_at,sensitive_expires_at
                    from outbox_actions
                    where sensitive_expires_at > ? and available_at <= ?
                      and (status='PENDING' or (status='PROCESSING' and lease_expires_at <= ?
                        and (action_type<>'intelligent_alert' or not exists (
                            select 1 from decision_delivery_attempts attempt where attempt.channel='TELEGRAM'
                              and attempt.status in ('DISPATCHED','UNKNOWN')
                              and attempt.decision_id::text=right(outbox_actions.action_key,36)))))
                    order by available_at,created_at,id
                    for update skip locked
                    limit 1
                    """, (rs, row) -> new OutboxAction(rs.getLong("id"), rs.getString("action_key"),
                    rs.getString("action_type"), rs.getString("payload"), rs.getString("pending_snapshot"),
                    rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("sensitive_expires_at").toInstant(), null),
                    Timestamp.from(rateLimit.databaseNow()), Timestamp.from(rateLimit.databaseNow()),
                    Timestamp.from(rateLimit.databaseNow()));
            if (candidates.isEmpty()) return null;

            OutboxAction candidate = candidates.getFirst();
            UUID token = UUID.randomUUID();
            int updated = jdbc.update("""
                    update outbox_actions
                    set status='PROCESSING', attempts=attempts+1, lease_token=?, lease_expires_at=?, updated_at=?
                    where id=?
                    """, token, Timestamp.from(rateLimit.databaseNow().plusSeconds(LEASE_SECONDS)),
                    Timestamp.from(rateLimit.databaseNow()), candidate.id());
            if (updated != 1) return null;
            if (!telegramSendInterval.isZero()) {
                jdbc.update("update telegram_delivery_rate_limit set next_allowed_at=? where singleton_id=1",
                        Timestamp.from(rateLimit.databaseNow().plus(telegramSendInterval)));
            }
            return new OutboxAction(candidate.id(), candidate.actionKey(), candidate.actionType(), candidate.payload(),
                    candidate.pendingSnapshot(), candidate.attempts() + 1, candidate.createdAt(), candidate.sensitiveExpiresAt(), token.toString());
        });
    }

    public boolean acknowledge(OutboxAction action, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            // RETURNING uses the stored immutable key, never a caller/model-supplied payload.
            List<String> acknowledged = jdbc.query("""
                    update outbox_actions set status='ACKNOWLEDGED',payload='{}'::jsonb,pending_snapshot='[]'::jsonb,
                        sensitive_expires_at=?,lease_token=null,lease_expires_at=null,last_error=null,updated_at=?
                    where id=? and status='PROCESSING' and lease_token=?::uuid
                    returning action_type,action_key
                    """, (rs,row) -> rs.getString("action_type") + "\n" + rs.getString("action_key"),
                    Timestamp.from(now), Timestamp.from(now), action.id(), action.leaseToken());
            if (acknowledged.isEmpty()) return false;
            String stored = acknowledged.getFirst();
            if (stored.startsWith("intelligent_alert\nintelligent_alert:")) {
                UUID decisionId = UUID.fromString(stored.substring("intelligent_alert\nintelligent_alert:".length()));
                deliveries.finish(decisionId,"TELEGRAM",UUID.fromString(action.leaseToken()),"CONFIRMED",now);
                jdbc.update("""
                        insert into decision_cooldowns(chat_jid,participant_jid,last_real_action_at,last_real_decision_id)
                        select chat_jid,'',?,batch_id from simulation_batches where batch_id=?
                        on conflict(chat_jid,participant_jid) do update
                        set last_real_action_at=excluded.last_real_action_at,last_real_decision_id=excluded.last_real_decision_id
                        where decision_cooldowns.last_real_action_at is null
                           or decision_cooldowns.last_real_action_at<=excluded.last_real_action_at
                        """, Timestamp.from(now),decisionId);
            }
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
        retry(action, now, errorType, null);
    }

    public void retry(OutboxAction action, Instant now, String errorType, Duration retryAfter) {
        retry(action, now, errorType, retryAfter != null, retryAfter);
    }

    public void retry(OutboxAction action, Instant now, String errorType, boolean rateLimited, Duration retryAfter) {
        int exponent = Math.min(30, Math.max(0, action.attempts() - 1));
        long backoff = Math.min(MAX_BACKOFF_SECONDS, 10L << exponent);
        boolean serverRequestedRetry = retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero();
        transactions.executeWithoutResult(status -> {
            if (rateLimited) lockTelegramRateLimitRow();
            Instant databaseNow = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
            Instant retryAt;
            if (rateLimited) {
                Duration delay = serverRequestedRetry ? retryAfter : DEFAULT_RATE_LIMIT_COOLDOWN;
                retryAt = databaseNow.plus(delay).plus(RETRY_AFTER_MARGIN);
                advanceTelegramRateLimit(retryAt);
            } else {
                retryAt = databaseNow.plusSeconds(backoff);
            }
            jdbc.update("""
                    update outbox_actions set status='PENDING',lease_token=null,lease_expires_at=null,
                        available_at=?,last_error=?,updated_at=?
                    where id=? and status='PROCESSING' and lease_token=?::uuid
                    """, Timestamp.from(retryAt), sanitizeError(errorType), Timestamp.from(databaseNow), action.id(), action.leaseToken());
        });
    }

    private void advanceTelegramRateLimit(Instant retryAt) {
        Timestamp until = Timestamp.from(retryAt);
        jdbc.update("""
                update telegram_delivery_rate_limit
                set next_allowed_at=case when next_allowed_at is null or next_allowed_at < ? then ? else next_allowed_at end
                where singleton_id=1
                """, until, until);
    }

    private Timestamp lockTelegramRateLimitRow() {
        List<Timestamp> rows = jdbc.query("""
                select next_allowed_at from telegram_delivery_rate_limit where singleton_id=1 for update
                """, (rs, row) -> rs.getTimestamp("next_allowed_at"));
        if (rows.isEmpty()) throw new IllegalStateException("Telegram rate-limit state is missing");
        return rows.getFirst();
    }

    private void clearIfUnchanged(String snapshotJson) {
        List<PendingSnapshot> snapshots = jdbc.query("""
                select chat_jid,message_count,first_message_at,last_message_at,updated_at,pending_generation
                from jsonb_to_recordset(?::jsonb) as snapshot(
                    chat_jid text,message_count integer,first_message_at text,last_message_at text,updated_at timestamptz,pending_generation text)
                """, (rs, row) -> new PendingSnapshot(rs.getString("chat_jid"), rs.getInt("message_count"),
                rs.getString("first_message_at"), rs.getString("last_message_at"),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("pending_generation")), snapshotJson);
        for (PendingSnapshot snapshot : snapshots) {
            jdbc.update("""
                    delete from pending_notification p
                    where p.chat_jid=?
                      and p.payload->>'messageCount'=? and p.payload->>'firstMessageAt'=?
                      and p.payload->>'lastMessageAt'=? and p.updated_at=? and p.generation=?::uuid
                      and exists (
                          select 1 from archive_monitor_settings settings
                          where settings.singleton_id=1 and (
                              settings.mode='ALL'
                              or exists (
                                  select 1 from chat_snapshot c
                                  where c.chat_jid=p.chat_jid and c.synchronized_snapshot is true
                                    and ((settings.mode='ARCHIVED_ONLY' and c.archived is true)
                                      or (settings.mode='UNARCHIVED_ONLY' and c.archived is false))
                              )
                          )
                      )
                    """, snapshot.chatJid(), Integer.toString(snapshot.messageCount()), snapshot.firstMessageAt(),
                    snapshot.lastMessageAt(), Timestamp.from(snapshot.updatedAt()), snapshot.generation());
        }
    }

    private static String sanitizeError(String errorType) {
        if (errorType == null || !errorType.matches("[A-Za-z0-9_.-]{1,100}")) return "DeliveryFailure";
        return errorType;
    }

    private record PendingSnapshot(String chatJid, int messageCount, String firstMessageAt,
                                   String lastMessageAt, Instant updatedAt, String generation) { }

    private record RateLimitState(Timestamp nextAllowedAt, Instant databaseNow) { }
}
