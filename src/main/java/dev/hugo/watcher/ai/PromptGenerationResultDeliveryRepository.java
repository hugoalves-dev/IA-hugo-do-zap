package dev.hugo.watcher.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Payload-free projection of a prompt result action into its durable job row. */
@Repository
public class PromptGenerationResultDeliveryRepository {
    private static final Logger log = LoggerFactory.getLogger(PromptGenerationResultDeliveryRepository.class);
    private static final String ACTION_PREFIX = "prompt-generation-result:";
    private static final String ACTION_TYPE = "telegram_admin_reply";
    private static final Set<String> OUTBOX_STATES = Set.of("PENDING", "PROCESSING", "ACKNOWLEDGED", "CANCELLED");
    private static final Set<String> PRESERVED_EXPIRY_STATES = Set.of(
            "ACKNOWLEDGED", "SUPPRESSED", "CANCELLED", "DEAD_LETTER");

    private final JdbcTemplate jdbc;
    private final PromptGenerationMetrics metrics;

    @Autowired
    public PromptGenerationResultDeliveryRepository(JdbcTemplate jdbc, PromptGenerationMetrics metrics) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.metrics = metrics;
    }

    /** Legacy constructor for standalone repository tests. */
    public PromptGenerationResultDeliveryRepository(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    public void sync(String actionKey) { sync(actionKey, null); }

    public void suppress(String actionKey) { sync(actionKey, "SUPPRESSED"); }

    public void deadLetter(String actionKey) { sync(actionKey, "DEAD_LETTER"); }

    /** Mirrors delivery metadata before RetentionRepository removes an expired outbox row. */
    public void expireOutboxRows(Instant cutoff) {
        List<String> keys = jdbc.query("""
                select action_key from outbox_actions
                where action_key like 'prompt-generation-result:%' and action_type=? and sensitive_expires_at<=?
                order by sensitive_expires_at,id for update skip locked
                """, (rs, row) -> rs.getString(1), ACTION_TYPE, Timestamp.from(cutoff));
        for (String key : keys) syncExpired(key, cutoff);
    }

    private void sync(String actionKey, String overrideStatus) {
        UUID jobId = jobId(actionKey);
        if (jobId == null) return;
        String query = """
                select action_type,status,attempts,last_error,rabbit_confirmed_at,sensitive_expires_at
                from outbox_actions where action_key=?
                """ + ("DEAD_LETTER".equals(overrideStatus) ? " for update" : "");
        List<DeliveryState> rows = jdbc.query(query, (rs, row) -> new DeliveryState(rs.getString("action_type"), rs.getString("status"), rs.getInt("attempts"),
                rs.getString("last_error"), rs.getTimestamp("rabbit_confirmed_at"),
                rs.getTimestamp("sensitive_expires_at")), actionKey);
        if (rows.isEmpty()) return;
        DeliveryState state = rows.getFirst();
        if (!ACTION_TYPE.equals(state.actionType())) return;
        String status = overrideStatus == null ? state.status() : overrideStatus;
        if (!OUTBOX_STATES.contains(status) && !Set.of("SUPPRESSED", "DEAD_LETTER").contains(status)) return;
        update(jobId, actionKey, status, state);
    }

    private void syncExpired(String actionKey, Instant cutoff) {
        UUID jobId = jobId(actionKey);
        if (jobId == null) return;
        List<DeliveryState> rows = jdbc.query("""
                select action_type,status,attempts,last_error,rabbit_confirmed_at,sensitive_expires_at
                from outbox_actions where action_key=? and action_type=? and sensitive_expires_at<=?
                """, (rs, row) -> new DeliveryState(rs.getString("action_type"), rs.getString("status"), rs.getInt("attempts"),
                rs.getString("last_error"), rs.getTimestamp("rabbit_confirmed_at"),
                rs.getTimestamp("sensitive_expires_at")), actionKey, ACTION_TYPE, Timestamp.from(cutoff));
        if (rows.isEmpty()) return;
        DeliveryState state = rows.getFirst();
        List<String> summary = jdbc.query("select result_delivery_status from prompt_generation_jobs where job_id=?",
                (rs, row) -> rs.getString(1), jobId);
        if (summary.isEmpty()) return;
        String previous = summary.getFirst();
        String status = PRESERVED_EXPIRY_STATES.contains(previous) ? previous : "EXPIRED";
        // Distinguish a broker confirm from a Telegram transport attempt in durable diagnostics.
        boolean unconsumed = "EXPIRED".equals(status) && state.attempts() == 0
                && state.rabbitConfirmedAt() != null;
        DeliveryState terminal = unconsumed
                ? new DeliveryState(state.actionType(), state.status(), state.attempts(),
                        "RabbitConfirmedUnconsumed", state.rabbitConfirmedAt(), state.expiresAt())
                : state;
        update(jobId, actionKey, status, terminal);
        if (unconsumed)
            log.warn("prompt_generation_delivery event=RESULT_EXPIRED_UNCONSUMED jobId={} status=EXPIRED attempts=0",
                    jobId);
    }

    private void update(UUID jobId, String actionKey, String status, DeliveryState state) {
        int changed = jdbc.update("""
                update prompt_generation_jobs set result_delivery_status=?,result_delivery_attempts=?,
                    result_delivery_last_error=?,result_delivery_rabbit_confirmed_at=?,result_delivery_expires_at=?
                where job_id=?
                """, status, state.attempts(), state.lastError(), state.rabbitConfirmedAt(), state.expiresAt(), jobId);
        if (changed != 1) return;
        Runnable record = () -> {
            if (metrics != null) metrics.delivery(status);
            log.info("prompt_generation_delivery event=RESULT_DELIVERY_STATE jobId={} actionKey={} status={} attempts={} has_last_error={} rabbit_confirmed_at={} expires_at={}",
                    jobId, actionKey, status, state.attempts(), state.lastError() != null,
                    state.rabbitConfirmedAt() == null ? "NONE" : state.rabbitConfirmedAt().toInstant(),
                    state.expiresAt() == null ? "NONE" : state.expiresAt().toInstant());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { record.run(); }
            });
        } else {
            record.run();
        }
    }

    private static UUID jobId(String actionKey) {
        if (actionKey == null || !actionKey.startsWith(ACTION_PREFIX)) return null;
        String raw = actionKey.substring(ACTION_PREFIX.length());
        try {
            UUID id = UUID.fromString(raw);
            return id.toString().equals(raw) ? id : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private record DeliveryState(String actionType, String status, int attempts, String lastError,
                                 Timestamp rabbitConfirmedAt, Timestamp expiresAt) { }
}
