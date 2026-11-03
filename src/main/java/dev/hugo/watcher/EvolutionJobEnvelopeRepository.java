package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL-native job-envelope persistence.
 * The caller's transaction includes row locks, lease validation,
 * message processing, quarantine and job completion.
 */
@Repository
public class EvolutionJobEnvelopeRepository {
    private final JdbcTemplate jdbc;

    public EvolutionJobEnvelopeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks both rows together; never read payload outside the processing lease transaction. */
    public List<String> lockEnvelope(UUID jobId, UUID leaseToken) {
        return jdbc.query("""
                select inbox.payload::text
                  from processing_jobs job
                  join inbox_events inbox on inbox.id=job.inbox_event_id
                 where job.job_uuid=? and job.lease_token=? and job.status='PROCESSING'
                 for update of job, inbox
                """, (rs, row) -> rs.getString(1), jobId, leaseToken);
    }

    /** DB clock is evaluated after lock acquisition, not from a stale application timestamp. */
    public List<Validity> checkLeaseAndInbox(UUID jobId, UUID leaseToken) {
        return jdbc.query("""
                select job.lease_expires_at > clock_timestamp() as lease_valid,
                       inbox.expires_at > clock_timestamp() as inbox_valid
                  from processing_jobs job
                  join inbox_events inbox on inbox.id=job.inbox_event_id
                 where job.job_uuid=? and job.lease_token=? and job.status='PROCESSING'
                """, (rs, row) -> new Validity(rs.getBoolean("lease_valid"), rs.getBoolean("inbox_valid")),
                jobId, leaseToken);
    }

    /** Scrub expired payload without touching its immutable identity or its retry accounting. */
    public void scrubExpiredInbox(long inboxEventId) {
        jdbc.update("update inbox_events set payload='{}'::jsonb where id=?", inboxEventId);
    }

    public void quarantine(String externalId, String reason, Instant at) {
        QuarantineWriter.write(jdbc, "WEBHOOK", externalId, reason, at);
    }

    public record Validity(boolean leaseValid, boolean inboxValid) { }
}
