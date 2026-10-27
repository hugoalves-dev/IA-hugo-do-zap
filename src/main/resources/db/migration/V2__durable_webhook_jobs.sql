ALTER TABLE processing_jobs
    ADD COLUMN job_uuid UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN lease_token UUID NULL,
    ADD COLUMN lease_expires_at TIMESTAMPTZ NULL;

ALTER TABLE processing_jobs
    ADD CONSTRAINT uq_processing_jobs_job_uuid UNIQUE (job_uuid);

CREATE INDEX ix_processing_jobs_claimable
    ON processing_jobs (status, available_at, lease_expires_at, id);
