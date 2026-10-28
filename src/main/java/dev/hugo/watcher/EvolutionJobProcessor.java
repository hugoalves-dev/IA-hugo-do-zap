package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Consumes durable webhook jobs; unknown event metadata is quarantined rather than inferred. */
@Component
public class EvolutionJobProcessor {
    private static final System.Logger LOG = System.getLogger(EvolutionJobProcessor.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_JOBS_PER_PASS = 20;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JobRepository jobs;
    private final EvolutionEventMapper mapper;
    private final MessageService messages;
    private final Clock clock;

    public EvolutionJobProcessor(JdbcTemplate jdbc, JobRepository jobs, EvolutionEventMapper mapper,
                                 MessageService messages, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jobs = jobs;
        this.mapper = mapper;
        this.messages = messages;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${watcher.job-delay:PT1S}")
    public void processScheduled() {
        try {
            processAvailable();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.ERROR, "Webhook job poll failed: error={0}", ex.getClass().getSimpleName());
        }
    }

    /** Claims and processes one batch, returning the number of jobs completed in this pass. */
    public int processAvailable() {
        int completed = 0;
        for (int processed = 0; processed < MAX_JOBS_PER_PASS; processed++) {
            List<Job> claimed = jobs.claim(1, clock.instant(), LEASE);
            if (claimed.isEmpty()) return completed;
            Job job = claimed.getFirst();
            try {
                ProcessResult result = transactions.execute(status -> processAndComplete(job, clock.instant()));
                if (result == ProcessResult.COMPLETED) completed++;
                if (result == ProcessResult.STALE_LEASE) return completed;
            } catch (RuntimeException ex) {
                long seconds = Math.min(900L, 10L << Math.min(7, Math.max(0, job.attempts() - 1)));
                boolean rescheduled = jobs.retry(job.id(), job.leaseToken(), clock.instant(), clock.instant().plusSeconds(seconds));
                LOG.log(System.Logger.Level.WARNING, "Webhook job processing failed: error={0}, retryScheduled={1}",
                        ex.getClass().getSimpleName(), rescheduled);
                if (!rescheduled) return completed;
            }
        }
        return completed;
    }

    @SuppressWarnings("unchecked")
    private ProcessResult processAndComplete(Job job, Instant now) {
        List<String> rows = jdbc.query("""
                select inbox.payload::text
                  from processing_jobs job
                  join inbox_events inbox on inbox.id=job.inbox_event_id
                 where job.job_uuid=? and job.lease_token=? and job.status='PROCESSING'
                 for update of job, inbox
                """, (rs, row) -> rs.getString(1), job.id(), job.leaseToken());
        if (rows.isEmpty()) return ProcessResult.STALE_LEASE;
        List<Validity> validity = jdbc.query("""
                select job.lease_expires_at > clock_timestamp() as lease_valid,
                       inbox.expires_at > clock_timestamp() as inbox_valid
                  from processing_jobs job
                  join inbox_events inbox on inbox.id=job.inbox_event_id
                 where job.job_uuid=? and job.lease_token=? and job.status='PROCESSING'
                """, (rs, row) -> new Validity(rs.getBoolean("lease_valid"), rs.getBoolean("inbox_valid")),
                job.id(), job.leaseToken());
        if (validity.isEmpty() || !validity.getFirst().leaseValid()) return ProcessResult.STALE_LEASE;
        if (!validity.getFirst().inboxValid()) {
            jobs.expire(job.id(), job.leaseToken());
            jdbc.update("update inbox_events set payload='{}'::jsonb where id=?", job.inboxEventId());
            return ProcessResult.EXPIRED;
        }

        JsonNode envelope;
        try {
            envelope = JSON.readTree(rows.getFirst());
        } catch (RuntimeException ex) {
            quarantine(null, "invalid_persisted_envelope", now);
            jobs.complete(job.id(), job.leaseToken());
            return ProcessResult.COMPLETED;
        }
        JsonNode data = envelope == null ? null : envelope.get("data");
        if (data == null || !data.isObject()) {
            quarantine(null, "unsupported_or_unverified_envelope", now);
            jobs.complete(job.id(), job.leaseToken());
            return ProcessResult.COMPLETED;
        }
        List<EvolutionEvent> events = mapper.map(envelope, "webhook");
        if (events.size() != 1) {
            quarantine(null, "unsupported_or_unverified_envelope", now);
            jobs.complete(job.id(), job.leaseToken());
            return ProcessResult.COMPLETED;
        }

        EvolutionEvent event = events.getFirst();
        JsonNode key = data.get("key");
        JsonNode type = data.get("type");
        JsonNode fromMe = key == null ? null : key.get("fromMe");
        JsonNode message = data.get("message");
        if (!"notify".equals(text(type)) || fromMe == null || !fromMe.isBoolean()
                || message == null || !message.isObject() || event.timestamp().isEmpty()
                || event.jid().isEmpty()) {
            quarantine(event.externalId(), "unverified_message_metadata", now);
            jobs.complete(job.id(), job.leaseToken());
            return ProcessResult.COMPLETED;
        }

        boolean group = event.jid().orElseThrow().endsWith("@g.us");
        if (fromMe.booleanValue() && event.externalId() != null && !event.externalId().isBlank()
                && event.externalId().length() <= 255 && event.jid().orElseThrow().length() <= 255) {
            messages.recordOutgoingReference(event.jid().orElseThrow(), event.externalId(), event.timestamp().orElseThrow());
        }
        String participant = group ? text(key.get("participant")) : null;
        String alternateChat = group ? null : text(key.get("remoteJidAlt"));
        String alternateParticipant = group ? text(key.get("participantAlt")) : null;
        Map<String, Object> content = JSON.readValue(message.toString(), Map.class);
        messages.accept(new IncomingMessage(Optional.of(event.externalId()), event.jid().orElseThrow(),
                optional(participant), optional(alternateChat), optional(alternateParticipant), fromMe.booleanValue(),
                "notify", false, content, event.timestamp().orElseThrow()));
        jobs.complete(job.id(), job.leaseToken());
        return ProcessResult.COMPLETED;
    }

    private enum ProcessResult { COMPLETED, EXPIRED, STALE_LEASE }
    private record Validity(boolean leaseValid, boolean inboxValid) { }

    private void quarantine(String externalId, String reason, Instant now) {
        QuarantineWriter.write(jdbc, "WEBHOOK", externalId, reason, now);
    }

    private static String text(JsonNode node) {
        return node == null ? null : node.stringValueOpt().orElse(null);
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
