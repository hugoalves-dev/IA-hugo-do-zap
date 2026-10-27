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
                    select id from processing_jobs
                    where (status in ('READY', 'PENDING') and available_at <= ?)
                       or (status = 'PROCESSING' and lease_expires_at <= ?)
                    order by available_at, id
                    limit ?
                    for update skip locked
                )
                update processing_jobs as job
                   set status = 'PROCESSING',
                       attempts = job.attempts + 1,
                       lease_token = gen_random_uuid(),
                       lease_expires_at = ?
                  from candidates
                 where job.id = candidates.id
                returning job.job_uuid, job.inbox_event_id, job.lease_token, job.attempts
                """, JOB_ROW, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), limit,
                expiresAt.atOffset(ZoneOffset.UTC)));
    }

    @Override
    public void complete(UUID jobId, UUID leaseToken) {
        int updated = jdbc.update("""
                update processing_jobs
                   set status = 'COMPLETED', lease_token = null, lease_expires_at = null
                 where job_uuid = ? and lease_token = ? and status = 'PROCESSING'
                   and lease_expires_at > now()
                """, jobId, leaseToken);
        if (updated != 1) throw new IllegalStateException("Job lease is no longer current");
    }

    @Override
    public boolean retry(UUID jobId, UUID leaseToken, Instant now, Instant availableAt) {
        return jdbc.update("""
                update processing_jobs
                   set status = 'PENDING', available_at = ?, lease_token = null, lease_expires_at = null
                 where job_uuid = ? and lease_token = ? and status = 'PROCESSING'
                   and lease_expires_at > ?
                """, Timestamp.from(availableAt), jobId, leaseToken, Timestamp.from(now)) == 1;
    }
}
