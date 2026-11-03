package dev.hugo.watcher.simulation;

import dev.hugo.watcher.IncomingMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import dev.hugo.watcher.simulation.DebouncePersistenceRepository.OpenBatch;
import dev.hugo.watcher.simulation.DebouncePersistenceRepository.Capture;
import dev.hugo.watcher.simulation.DebouncePersistenceRepository.BatchHead;
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
    private final DebouncePersistenceRepository persistence;
    private final TransactionTemplate transactions;
    private final WatcherSimulationProperties limits;
    private final boolean processingEnabled;

    public DebounceService(DebouncePersistenceRepository persistence, WatcherSimulationProperties limits,
                           PlatformTransactionManager manager, boolean realModeEnabled) {
        this.persistence = persistence;
        this.limits = limits;
        this.transactions = new TransactionTemplate(manager);
        this.processingEnabled = limits.isEnabled() || realModeEnabled;
    }

    /** Compatibility adapter for standalone tests. */
    public DebounceService(JdbcTemplate jdbc, WatcherSimulationProperties limits,
                           PlatformTransactionManager manager, boolean realModeEnabled) {
        this(new DebouncePersistenceRepository(jdbc), limits, manager, realModeEnabled);
    }

    public DebounceService(JdbcTemplate jdbc, WatcherSimulationProperties limits,
                           PlatformTransactionManager manager) {
        this(jdbc, limits, manager, false);
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
        enqueueTransaction(message, at, at, at.plus(Duration.ofDays(limits.getSensitiveRetentionDays())));
    }

    private void enqueueTransaction(IncomingMessage message, Instant at, Instant capturedAt, Instant sensitiveExpiresAt) {
        lockIdentityMerge();
        String chat = canonical(message.chatJid());
        String participant = message.participantJid().map(this::canonical).orElse("");
        lockBatch(chat, participant);
        String messageId = message.externalId().orElseThrow(() -> new IllegalArgumentException("external message ID required"));
        Timestamp now = Timestamp.from(at);
        List<UUID> duplicate = persistence.duplicate(chat, participant, messageId);
        if (!duplicate.isEmpty()) {
            persistence.shortenExpiry(duplicate.getFirst(), sensitiveExpiresAt);
            return;
        }

        List<OpenBatch> open = persistence.openBatches(chat, participant);
        UUID id;
        if (open.isEmpty()) {
            id = createBatch(chat, participant, now, Timestamp.from(at.plusSeconds(limits.getInactivitySeconds())), capturedAt, sensitiveExpiresAt);
        } else {
            OpenBatch head = open.getFirst();
            id = head.id();
            Integer size = persistence.batchSize(id);
            boolean earlier = at.isBefore(head.firstAt());
            boolean separateLateWindow = earlier && (Duration.between(at,head.firstAt()).getSeconds() >= limits.getInactivitySeconds()
                    || !at.plusSeconds(limits.getMaxWaitSeconds()).isAfter(head.lastAt())
                    || size != null && size >= limits.getMaxMessages());
            if (separateLateWindow) {
                // A late window must not close or rewrite a newer OPEN batch (or a persisted decision).
                id = createBatch(chat,participant,now,Timestamp.from(at.plusSeconds(limits.getInactivitySeconds())),
                        capturedAt,sensitiveExpiresAt,true);
            } else if (size != null && size >= limits.getMaxMessages()) {
                persistence.closeBatch(id, at);
                id = createBatch(chat, participant, now, now, capturedAt, sensitiveExpiresAt);
            } else {
                persistence.extendBatch(id, at, sensitiveExpiresAt,
                        limits.getMaxWaitSeconds(), limits.getInactivitySeconds());
            }
        }
        try {
            String payload = JSON.writeValueAsString(message);
            persistence.insertMessage(id, chat, participant, messageId,
                    message.receivedAt() == null ? at : message.receivedAt(), payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("unable to snapshot incoming message", ex);
        }
    }

    private UUID createBatch(String chat, String participant, Timestamp now, Timestamp dueAt,
                             Instant capturedAt, Instant sensitiveExpiresAt) {
        return createBatch(chat,participant,now,dueAt,capturedAt,sensitiveExpiresAt,false);
    }

    private UUID createBatch(String chat, String participant, Timestamp now, Timestamp dueAt,
                             Instant capturedAt, Instant sensitiveExpiresAt, boolean ready) {
        persistence.lockBatchCapacity();
        Integer active = persistence.activeBatchCount();
        if (active != null && active >= limits.getMaxPendingBatches()) throw new IllegalStateException("simulation batch capacity reached");
        UUID id = UUID.randomUUID();
        Instant configuredDeadline = capturedAt.plus(Duration.ofDays(limits.getSensitiveRetentionDays()));
        Instant deadline = sensitiveExpiresAt.isBefore(configuredDeadline) ? sensitiveExpiresAt : configuredDeadline;
        persistence.insertBatch(id, chat, participant, ready, now.toInstant(), dueAt.toInstant(),
                capturedAt, deadline, capturedAt.plus(Duration.ofDays(limits.getAuditRetentionDays())));
        return id;
    }

    private void lockBatch(String chat, String participant) {
        persistence.lockBatch(chat, participant);
    }

    public List<Batch> claimReady(Instant now) {
        if (!processingEnabled) return List.of();
        drainInbox(now);
        return Objects.requireNonNull(transactions.execute(status -> claimTransaction(now)));
    }

    private void drainInbox(Instant now) {
        if (!processingEnabled) return;
        // One transaction per capture. A failed enqueue retains the durable snapshot for retry.
        for (int i = 0; i < 100; i++) {
            Long[] selectedId = { null };
            try {
                Boolean drained = transactions.execute(status -> {
                    lockIdentityMerge();
                    List<Capture> rows = persistence.nextCapture(now);
                    if (rows.isEmpty()) return false;
                    Capture capture = rows.getFirst();
                    selectedId[0] = capture.id();
                    IncomingMessage message = JSON.readValue(capture.payload(), IncomingMessage.class);
                    Instant receivedAt = message.receivedAt() == null ? capture.capturedAt() : message.receivedAt();
                    // Replay original windows, not the time at which a delayed poll drains the capture.
                    persistence.closeDueBatch(canonical(message.chatJid()),
                            message.participantJid().map(this::canonical).orElse(""), receivedAt);
                    enqueueTransaction(message, receivedAt, capture.capturedAt(), capture.expiresAt());
                    persistence.clearCapture(capture.id());
                    return true;
                });
                if (!Boolean.TRUE.equals(drained)) break;
            } catch (RuntimeException failure) {
                // No JIDs, payloads or database error text in logs.
                org.slf4j.LoggerFactory.getLogger(DebounceService.class)
                        .warn("Shadow capture deferred; durable snapshot retained for retry");
                if (selectedId[0] == null) break;
                try {
                    // Backoff survives restart and never changes payload retention or origin timestamps.
                    transactions.executeWithoutResult(status ->
                            persistence.retryCapture(selectedId[0], now.plusSeconds(30)));
                } catch (RuntimeException unavailable) {
                    break;
                }
            }
        }
    }

    private void lockIdentityMerge() {
        persistence.lockIdentityMerge();
    }

    private String canonical(String jid) {
        return persistence.canonical(jid);
    }

    private List<Batch> claimTransaction(Instant now) {
        lockIdentityMerge();
        persistence.advanceDue(now);
        persistence.reclaimStale(now.minusSeconds(60));
        List<UUID> ids = persistence.claimableBatches(now);
        List<Batch> claimed = new ArrayList<>();
        for (UUID id : ids) {
            persistence.markClaimed(id, now);
            List<String> rows = persistence.batchMessages(id);
            List<IncomingMessage> messages = rows.stream().map(row -> {
                try { return JSON.readValue(row, IncomingMessage.class); }
                catch (Exception ex) { throw new IllegalStateException("invalid persisted message snapshot", ex); }
            }).toList();
            List<BatchHead> heads = persistence.batchHeads(id);
            if (!heads.isEmpty()) claimed.add(new Batch(id, heads.getFirst().chat(), heads.getFirst().participant(), messages));
        }
        return List.copyOf(claimed);
    }

}
