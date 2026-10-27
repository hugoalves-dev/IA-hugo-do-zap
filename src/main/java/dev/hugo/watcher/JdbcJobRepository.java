package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcJobRepository implements JobRepository {
    private static final RowMapper<Job> JOB_ROW = (result, rowNum) -> new Job(
            result.getObject("job_uuid", UUID.class),
            result.getLong("inbox_event_id"),
            result.getObject("lease_token", UUID.class),
            result.getInt("attempts"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcJobRepository(JdbcTemplate jdbc, DataSourceTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<Job> claim(int limit, Instant now, Duration leaseDuration) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) throw new IllegalArgumentException("leaseDuration must be positive");
        Instant expiresAt = now.plus(leaseDuration);
        return transactions.execute(status -> jdbc.query("""
                with candidates as (
                    select job.id from processing_jobs job
                    join inbox_events inbox on inbox.id=job.inbox_event_id
                    where inbox.expires_at > ?
                      and ((job.status in ('READY', 'PENDING') and job.available_at <= ?)
                       or (job.status = 'PROCESSING' and job.lease_expires_at <= ?))
                    order by job.available_at, job.id
                    limit ?
                    for update of job skip locked
                )
                update processing_jobs as job
                   set status = 'PROCESSING',
                       attempts = job.attempts + 1,
                       lease_token = gen_random_uuid(),
                       lease_expires_at = ?
                  from candidates
                 where job.id = candidates.id
                returning job.job_uuid, job.inbox_event_id, job.lease_token, job.attempts
                """, JOB_ROW, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC), limit,
                expiresAt.atOffset(ZoneOffset.UTC)));
    }

    @Override
    public void complete(UUID jobId, UUID leaseToken) {
        int updated = jdbc.update("""
                update processing_jobs job
                   set status = 'COMPLETED', lease_token = null, lease_expires_at = null
                  from inbox_events inbox
                 where inbox.id=job.inbox_event_id and job.job_uuid = ? and job.lease_token = ?
                   and job.status = 'PROCESSING' and job.lease_expires_at > clock_timestamp()
                   and inbox.expires_at > clock_timestamp()
                """, jobId, leaseToken);
        if (updated != 1) throw new IllegalStateException("Job lease is no longer current");
    }

    @Override
    public boolean expire(UUID jobId, UUID leaseToken) {
        return jdbc.update("""
                update processing_jobs
                   set status='EXPIRED', lease_token=null, lease_expires_at=null
                 where job_uuid=? and lease_token=? and status='PROCESSING'
                """, jobId, leaseToken) == 1;
    }

    @Override
    public boolean retry(UUID jobId, UUID leaseToken, Instant now, Instant availableAt) {
        return jdbc.update("""
                update processing_jobs job
                   set status = case when inbox.expires_at <= clock_timestamp() then 'EXPIRED' else 'PENDING' end,
                       available_at = ?, lease_token = null, lease_expires_at = null
                  from inbox_events inbox
                 where inbox.id=job.inbox_event_id and job.job_uuid = ? and job.lease_token = ?
                   and job.status = 'PROCESSING' and job.lease_expires_at > clock_timestamp()
                """, Timestamp.from(availableAt), jobId, leaseToken) == 1;
    }
}
