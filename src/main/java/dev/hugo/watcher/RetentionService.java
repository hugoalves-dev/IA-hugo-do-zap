package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Removes message snippets and quarantine identifiers at their bounded retention deadline. */
@Service
public class RetentionService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public RetentionService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 60_000L)
    public void purgeExpired() { purge(Instant.now()); }

    public int purge(Instant now) {
        Integer count = transactions.execute(status -> purgeInTransaction(now));
        return count == null ? 0 : count;
    }

    private int purgeInTransaction(Instant now) {
        Timestamp cutoff = Timestamp.from(now);
        int expiredJobs = jdbc.update("""
                with expired as (
                    select job.id
                      from processing_jobs job
                      join inbox_events inbox on inbox.id=job.inbox_event_id
                     where inbox.expires_at <= ? and job.status not in ('EXPIRED', 'COMPLETED')
                     order by job.id
                     for update of job skip locked
                )
                update processing_jobs job
                   set status='EXPIRED', lease_token=null, lease_expires_at=null
                  from expired
                 where expired.id=job.id
                """, cutoff);
        // Keep the event key and receipt metadata for deduplication; erase the raw webhook body at expiry.
        int scrubbedInboxPayloads = jdbc.update("""
                update inbox_events set payload='{}'::jsonb
                where expires_at <= ? and payload <> '{}'::jsonb
                  and not exists (select 1 from processing_jobs job
                                   where job.inbox_event_id=inbox_events.id and job.status='PROCESSING')
                """, cutoff);
        int pending = jdbc.update("delete from pending_notification where expires_at <= ?", cutoff);
        int scrubbedPreviews = scrubExpiredPreviews(now);
        int outboxPayloads = jdbc.update("delete from outbox_actions where sensitive_expires_at <= ?", cutoff);
        int expiredWhatsappActions = jdbc.update("""
                delete from whatsapp_action_outbox where action_id in (
                    select action_id from whatsapp_action_outbox where expires_at<=?
                     order by expires_at for update skip locked
                )
                """, cutoff);
        int expiredOwnerReferences = jdbc.update("delete from whatsapp_owner_message_references where expires_at <= ?", cutoff);
        int expiredOwnerContext = jdbc.update("delete from simulation_owner_context where expires_at <= ?", cutoff);
        int quarantined = jdbc.update("delete from quarantined_events where sensitive_expires_at <= ?", cutoff);
        int shadowPayloads = jdbc.update("update processed_message set shadow_payload=null,shadow_expires_at=null where shadow_expires_at <= ? and shadow_payload is not null", cutoff);
        int deduplication = jdbc.update("delete from processed_message where processed_at <= ?",
                Timestamp.from(now.minusSeconds(7 * 86_400L)));
        int simulationPayloads = jdbc.update("delete from simulation_batch_messages where batch_id in (select batch_id from simulation_batches where expires_at <= ?)", cutoff);
        int expiredSimulationBatches = jdbc.update("update simulation_batches set status='EXPIRED', claimed_at=null where expires_at <= ? and status in ('OPEN','READY','CLAIMED')", cutoff);
        int expiredSimulationAudits = jdbc.update("delete from simulation_decisions where metadata_expires_at <= ?", cutoff);
        int expiredSimulationMetadata = jdbc.update("""
                delete from simulation_batches batch
                where batch.metadata_expires_at <= ?
                  and not exists (select 1 from simulation_decisions decision where decision.batch_id=batch.batch_id)
                """, cutoff);
        return pending + scrubbedPreviews + expiredJobs + scrubbedInboxPayloads + outboxPayloads + expiredWhatsappActions + expiredOwnerReferences + expiredOwnerContext + quarantined + deduplication
                + shadowPayloads + simulationPayloads + expiredSimulationBatches + expiredSimulationAudits + expiredSimulationMetadata;
    }

    private int scrubExpiredPreviews(Instant now) {
        List<PendingPreview> rows = jdbc.query("""
                select chat_jid,payload::text as payload,expires_at
                from pending_notification where expires_at > ? and jsonb_exists(payload, 'preview')
                """, (rs, row) -> new PendingPreview(rs.getString("chat_jid"), rs.getString("payload"),
                rs.getTimestamp("expires_at").toInstant()), Timestamp.from(now));
        int scrubbed = 0;
        for (PendingPreview row : rows) {
            Instant previewExpiresAt = previewExpiresAt(row.payload(), row.expiresAt());
            if (!previewExpiresAt.isAfter(now)) {
                scrubbed += jdbc.update("update pending_notification set payload=payload-'preview' where chat_jid=? and expires_at>? and jsonb_exists(payload, 'preview') and payload=?::jsonb and expires_at=?",
                        row.chatJid(), Timestamp.from(now), row.payload(), Timestamp.from(row.expiresAt()));
            }
        }
        return scrubbed;
    }

    private static Instant previewExpiresAt(String payload, Instant fallback) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = JSON.readValue(payload, Map.class);
            Object value = fields.get("previewExpiresAt");
            if (value instanceof String timestamp) return Instant.parse(timestamp);
        } catch (RuntimeException ignored) {
            // An unreadable preview expiry is treated as expired.
            return Instant.MIN;
        }
        return fallback;
    }

    private record PendingPreview(String chatJid, String payload, Instant expiresAt) { }
}
