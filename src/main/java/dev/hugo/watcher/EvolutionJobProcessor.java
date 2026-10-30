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
import java.util.Set;

/** Consumes durable webhook jobs; unknown event metadata is quarantined rather than inferred. */
@Component
public class EvolutionJobProcessor {
    private static final System.Logger LOG = System.getLogger(EvolutionJobProcessor.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_JOBS_PER_PASS = 20;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Set<String> EVOLUTION_LIVE_MESSAGE_TYPES = Set.of(
            "conversation", "extendedTextMessage", "imageMessage", "videoMessage", "documentMessage",
            "audioMessage", "stickerMessage", "contactMessage", "contactsArrayMessage",
            "locationMessage", "liveLocationMessage", "ephemeralMessage",
            "viewOnceMessage", "viewOnceMessageV2", "viewOnceMessageV2Extension",
            "documentWithCaptionMessage");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JobRepository jobs;
    private final EvolutionEventMapper mapper;
    private final MessageService messages;
    private final IdentityService identities;
    private final Clock clock;

    public EvolutionJobProcessor(JdbcTemplate jdbc, JobRepository jobs, EvolutionEventMapper mapper,
                                 MessageService messages, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jobs = jobs;
        this.mapper = mapper;
        this.messages = messages;
        this.identities = new IdentityService(jdbc, transactionManager);
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
        String eventType = envelope == null ? null : text(envelope.get("event"));
        if ("contacts.upsert".equals(eventType) || "contacts.update".equals(eventType)) {
            if (data != null && data.isArray()) {
                for (JsonNode contact : data) observeContact(contact);
            } else if (data != null && data.isObject()) {
                observeContact(data);
            } else {
                quarantine(null, "unsupported_or_unverified_envelope", now);
            }
            jobs.complete(job.id(), job.leaseToken());
            return ProcessResult.COMPLETED;
        }
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
        boolean liveMessage = "notify".equals(text(type))
                || isFreshEvolutionUpsertWithoutBaileysType(type, data, message, event, now);
        if (!liveMessage || fromMe == null || !fromMe.isBoolean()
                || message == null || !message.isObject() || event.timestamp().isEmpty()
                || event.jid().isEmpty()) {
            quarantine(event.externalId(), "unverified_message_metadata", now);
            jobs.complete(job.id(), job.leaseToken());
            return ProcessResult.COMPLETED;
        }

        boolean group = event.jid().orElseThrow().endsWith("@g.us");
        if (!group && !fromMe.booleanValue() && contactJid(event.jid().orElseThrow())) {
            String name = text(data.get("pushName"));
            if (name != null) identities.observeContactName(event.jid().orElseThrow(), name, ContactNameSource.NOTIFY);
        }
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

    /**
     * Evolution v2.3.7 emits message metadata (status/messageType) but not Baileys' upsert "type".
     * Accept only a fresh, recognized message, never an explicit historical/append upsert.
     * A delayed initial/history sync without a reliable live signal remains quarantined.
     */
    private static boolean isFreshEvolutionUpsertWithoutBaileysType(
            JsonNode type, JsonNode data, JsonNode message, EvolutionEvent event, Instant now) {
        if (type != null && !type.isNull()) return false;
        if (data.path("isHistory").asBoolean(false) || data.path("history").asBoolean(false)) return false;

        String status = text(data.get("status"));
        if (!"DELIVERY_ACK".equals(status) && !"SERVER_ACK".equals(status)) return false;

        String messageType = text(data.get("messageType"));
        if (messageType == null || !EVOLUTION_LIVE_MESSAGE_TYPES.contains(messageType)
                || message == null || !message.isObject()
                || message.get(messageType) == null || message.get(messageType).isNull()
                || event.timestamp().isEmpty()) return false;

        Instant messageAt = event.timestamp().orElseThrow();
        return !messageAt.isBefore(now.minus(Duration.ofMinutes(10)))
                && !messageAt.isAfter(now.plus(Duration.ofMinutes(2)));
    }

    private enum ProcessResult { COMPLETED, EXPIRED, STALE_LEASE }
    private void observeContact(JsonNode contact) {
        if (!contact.isObject()) return;
        String jid = text(contact.get("remoteJid"));
        String name = text(contact.get("pushName"));
        if (name == null || !contactJid(jid)) return;
        identities.observeContactName(jid, name, ContactNameSource.NOTIFY);
    }
    private static boolean contactJid(String jid) {
        return jid != null && jid.length() <= 255
                && jid.matches("(?i)^(?:[1-9][0-9]{7,14}(?::[0-9]+)?@s\\.whatsapp\\.net|[0-9]+@lid)$");
    }
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
