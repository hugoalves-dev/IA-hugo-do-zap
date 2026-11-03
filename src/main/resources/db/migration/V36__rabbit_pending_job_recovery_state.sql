ALTER TABLE processing_jobs
    ADD COLUMN rabbit_backlog_confirmed_at timestamptz;

CREATE INDEX processing_jobs_pending_rabbit_recovery_idx
    ON processing_jobs (id)
    WHERE status IN ('READY', 'PENDING', 'PROCESSING') AND rabbit_backlog_confirmed_at IS NULL;
