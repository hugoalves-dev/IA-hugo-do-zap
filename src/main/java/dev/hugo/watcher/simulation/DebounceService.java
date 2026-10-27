package dev.hugo.watcher.simulation;

import dev.hugo.watcher.IncomingMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class DebounceService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WatcherSimulationProperties limits;

    @Autowired
    public DebounceService(JdbcTemplate jdbc, WatcherSimulationProperties limits,
                           PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.limits = limits;
        this.transactions = new TransactionTemplate(manager);
    }

    public DebounceService(JdbcTemplate jdbc, WatcherSimulationProperties limits) {
        this(jdbc, limits, new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public void enqueue(IncomingMessage message, Instant at) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(at, "at");
        transactions.executeWithoutResult(status -> enqueueTransaction(message, at));
    }

    private void enqueueTransaction(IncomingMessage message, Instant at) {
        String chat = message.chatJid();
        String participant = message.participantJid().orElse("");
        lockBatch(chat, participant);
        String messageId = message.externalId().orElseThrow(() -> new IllegalArgumentException("external message ID required"));
        Timestamp now = Timestamp.from(at);
        Integer duplicate = jdbc.queryForObject("select count(*) from simulation_batch_messages where chat_jid=? and participant_jid=? and external_id=?", Integer.class,
                chat, participant, messageId);
        if (duplicate != null && duplicate > 0) return;

        List<UUID> open = jdbc.query("select batch_id from simulation_batches where chat_jid=? and participant_jid=? and status='OPEN' for update",
                (rs, row) -> rs.getObject(1, UUID.class), chat, participant);
        UUID id;
        if (open.isEmpty()) {
            id = createBatch(chat, participant, now, Timestamp.from(at.plusSeconds(limits.getInactivitySeconds())));
        } else {
            id = open.getFirst();
            Integer size = jdbc.queryForObject("select count(*) from simulation_batch_messages where batch_id=?", Integer.class, id);
            if (size != null && size >= limits.getMaxMessages()) {
                jdbc.update("update simulation_batches set status='READY', due_at=? where batch_id=?", now, id);
                id = createBatch(chat, participant, now, now);
            } else {
                jdbc.update("""
                        update simulation_batches set last_message_at=?, due_at=least(first_message_at + (? * interval '1 second'), ?)
                        where batch_id=?
                        """, now, limits.getMaxWaitSeconds(), Timestamp.from(at.plusSeconds(limits.getInactivitySeconds())), id);
            }
        }
        try {
            String payload = JSON.writeValueAsString(message);
            jdbc.update("""
                    insert into simulation_batch_messages(batch_id,chat_jid,participant_jid,external_id,received_at,payload)
                    values (?,?,?,?,?,?::jsonb)
                    """, id, chat, participant, messageId, Timestamp.from(message.receivedAt() == null ? at : message.receivedAt()), payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("unable to snapshot incoming message", ex);
        }
    }

    private UUID createBatch(String chat, String participant, Timestamp now, Timestamp dueAt) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(947221, 1)")) { statement.execute(); }
            return null;
        });
        Integer active = jdbc.queryForObject("select count(*) from simulation_batches where status in ('OPEN','READY','CLAIMED')", Integer.class);
        if (active != null && active >= limits.getMaxPendingBatches()) throw new IllegalStateException("simulation batch capacity reached");
        UUID id = UUID.randomUUID();
        Instant at = now.toInstant();
        jdbc.update("""
                insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at)
                values (?,?,?,'OPEN',?,?,?,?,?,?)
                """, id, chat, participant, now, now, dueAt, now,
                Timestamp.from(at.plus(Duration.ofDays(limits.getSensitiveRetentionDays()))),
                Timestamp.from(at.plus(Duration.ofDays(limits.getAuditRetentionDays()))));
        return id;
    }

    private void lockBatch(String chat, String participant) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, chat + "\n" + participant);
                statement.execute();
            }
            return null;
        });
    }

    public List<Batch> claimReady(Instant now) {
        return Objects.requireNonNull(transactions.execute(status -> claimTransaction(now)));
    }

    private List<Batch> claimTransaction(Instant now) {
        Timestamp cutoff = Timestamp.from(now);
        jdbc.update("update simulation_batches set status='READY' where status='OPEN' and due_at<=?", cutoff);
        jdbc.update("update simulation_batches set status='READY', claimed_at=null where status='CLAIMED' and claimed_at<=?",
                Timestamp.from(now.minusSeconds(60)));
        List<UUID> ids = jdbc.query("""
                select batch_id from simulation_batches where status='READY' and expires_at>?
                order by due_at, batch_id for update skip locked limit 100
                """, (rs, row) -> rs.getObject(1, UUID.class), cutoff);
        List<Batch> claimed = new ArrayList<>();
        for (UUID id : ids) {
            jdbc.update("update simulation_batches set status='CLAIMED', claimed_at=? where batch_id=?", cutoff, id);
            List<String> rows = jdbc.query("select payload::text from simulation_batch_messages where batch_id=? order by received_at,external_id",
                    (rs, row) -> rs.getString(1), id);
            List<IncomingMessage> messages = rows.stream().map(row -> {
                try { return JSON.readValue(row, IncomingMessage.class); }
                catch (Exception ex) { throw new IllegalStateException("invalid persisted message snapshot", ex); }
            }).toList();
            List<BatchHead> heads = jdbc.query("select chat_jid,participant_jid from simulation_batches where batch_id=?",
                    (rs, row) -> new BatchHead(rs.getString(1), rs.getString(2)), id);
            if (!heads.isEmpty()) claimed.add(new Batch(id, heads.getFirst().chat(), heads.getFirst().participant(), messages));
        }
        return List.copyOf(claimed);
    }

    private record BatchHead(String chat, String participant) { }
}
