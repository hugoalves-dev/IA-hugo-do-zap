package dev.hugo.watcher.simulation;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PostgreSQL-native debounce storage, leases and advisory locks, inside existing service transactions. */
@Repository
public class DebouncePersistenceRepository {
    private final JdbcTemplate jdbc;
    public DebouncePersistenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<UUID> duplicate(String chat, String participant, String messageId) {
        return jdbc.query("select batch_id from simulation_batch_messages where chat_jid=? and participant_jid=? and external_id=?",
                (rs, row) -> rs.getObject(1, UUID.class), chat, participant, messageId);
    }

    public void shortenExpiry(UUID batch, Instant expiry) {
        jdbc.update("update simulation_batches set expires_at=least(expires_at,?) where batch_id=?",
                Timestamp.from(expiry), batch);
    }

    public List<OpenBatch> openBatches(String chat, String participant) {
        return jdbc.query("select batch_id,first_message_at,last_message_at from simulation_batches where chat_jid=? and participant_jid=? and status='OPEN' for update",
                (rs, row) -> new OpenBatch(rs.getObject(1, UUID.class),
                        rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()), chat, participant);
    }

    public Integer batchSize(UUID batch) {
        return jdbc.queryForObject("select count(*) from simulation_batch_messages where batch_id=?", Integer.class, batch);
    }

    public void closeBatch(UUID id, Instant at) {
        jdbc.update("update simulation_batches set status='READY', due_at=? where batch_id=?", Timestamp.from(at), id);
    }

    public void extendBatch(UUID id, Instant at, Instant expiry, int maxWaitSeconds, int inactivitySeconds) {
        Timestamp now = Timestamp.from(at);
        jdbc.update("""
                update simulation_batches set first_message_at=least(first_message_at,?),last_message_at=greatest(last_message_at,?),
                    due_at=least(least(first_message_at,?) + (? * interval '1 second'),
                                 greatest(last_message_at,?) + (? * interval '1 second')),
                    expires_at=least(expires_at, ?)
                where batch_id=?
                """, now, now, now, maxWaitSeconds, now, inactivitySeconds, Timestamp.from(expiry), id);
    }

    public void insertMessage(UUID id, String chat, String participant, String messageId, Instant receivedAt, String json) {
        jdbc.update("""
                insert into simulation_batch_messages(batch_id,chat_jid,participant_jid,external_id,received_at,payload)
                values (?,?,?,?,?,?::jsonb)
                """, id, chat, participant, messageId, Timestamp.from(receivedAt), json);
    }

    public void lockBatchCapacity() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(947221, 1)")) {
                statement.execute();
            }
            return null;
        });
    }

    public Integer activeBatchCount() {
        return jdbc.queryForObject("select count(*) from simulation_batches where status in ('OPEN','READY','CLAIMED')", Integer.class);
    }

    public void insertBatch(UUID id, String chat, String participant, boolean ready, Instant first,
                            Instant dueAt, Instant capturedAt, Instant sensitiveExpiry, Instant metadataExpiry) {
        jdbc.update("""
                insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at)
                values (?,?,?,?,?,?,?,?,?,?)
                """, id, chat, participant, ready ? "READY" : "OPEN", Timestamp.from(first), Timestamp.from(first),
                Timestamp.from(dueAt), Timestamp.from(capturedAt), Timestamp.from(sensitiveExpiry),
                Timestamp.from(metadataExpiry));
    }

    public void lockBatch(String chat, String participant) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, chat + "\n" + participant);
                statement.execute();
            }
            return null;
        });
    }

    public List<Capture> nextCapture(Instant now) {
        return jdbc.query("""
                select id,shadow_payload::text,processed_at,shadow_expires_at from processed_message
                 where shadow_payload is not null and shadow_expires_at>? and shadow_retry_at<=?
                 order by case when pg_input_is_valid(shadow_payload->>'receivedAt','timestamp with time zone')
                     then (shadow_payload->>'receivedAt')::timestamptz else processed_at end,id
                 for update skip locked limit 1
                """, (rs, row) -> new Capture(rs.getLong(1), rs.getString(2),
                rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant()), Timestamp.from(now),Timestamp.from(now));
    }

    public void closeDueBatch(String chat, String participant, Instant receivedAt) {
        jdbc.update("""
                update simulation_batches set status='READY'
                 where chat_jid=? and participant_jid=? and status='OPEN' and due_at<=?
                """, chat, participant, Timestamp.from(receivedAt));
    }

    public void clearCapture(long id) {
        jdbc.update("update processed_message set shadow_payload=null,shadow_expires_at=null where id=?", id);
    }

    public void retryCapture(long id, Instant nextRetry) {
        jdbc.update("update processed_message set shadow_retry_at=? where id=? and shadow_payload is not null",
                Timestamp.from(nextRetry), id);
    }

    public void lockIdentityMerge() {
        jdbc.query("select pg_advisory_xact_lock(947221, 2)", rs -> { });
    }

    public String canonical(String jid) {
        List<String> rows = jdbc.query("select canonical_identity from identity_aliases where alias=?",
                (rs, row) -> rs.getString(1), jid);
        return rows.isEmpty() ? jid : rows.getFirst();
    }

    public void advanceDue(Instant now) {
        jdbc.update("update simulation_batches set status='READY' where status='OPEN' and due_at<=?", Timestamp.from(now));
    }

    public void reclaimStale(Instant cutoff) {
        jdbc.update("update simulation_batches set status='READY', claimed_at=null where status='CLAIMED' and claimed_at<=?",
                Timestamp.from(cutoff));
    }

    public List<UUID> claimableBatches(Instant now) {
        return jdbc.query("""
                select batch_id from simulation_batches where status='READY' and expires_at>?
                order by due_at, batch_id for update skip locked limit 100
                """, (rs, row) -> rs.getObject(1, UUID.class), Timestamp.from(now));
    }

    public void markClaimed(UUID id, Instant now) {
        jdbc.update("update simulation_batches set status='CLAIMED', claimed_at=? where batch_id=?", Timestamp.from(now), id);
    }

    public List<String> batchMessages(UUID id) {
        return jdbc.query("select payload::text from simulation_batch_messages where batch_id=? order by received_at,external_id",
                (rs, row) -> rs.getString(1), id);
    }

    public List<BatchHead> batchHeads(UUID id) {
        return jdbc.query("select chat_jid,participant_jid from simulation_batches where batch_id=?",
                (rs, row) -> new BatchHead(rs.getString(1), rs.getString(2)), id);
    }

    public record OpenBatch(UUID id, Instant firstAt, Instant lastAt) { }
    public record Capture(long id, String payload, Instant capturedAt, Instant expiresAt) { }
    public record BatchHead(String chat, String participant) { }
}
