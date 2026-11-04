CREATE TABLE prompt_generation_jobs (
    job_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL,
    expected_draft_version BIGINT NOT NULL CHECK (expected_draft_version >= 0),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    chat_id BIGINT NOT NULL CHECK (chat_id > 0),
    operation VARCHAR(12) NOT NULL CHECK (operation IN ('GENERATE','REFINE')),
    instructions VARCHAR(4096) NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING','DISPATCHED','COMPLETE','FAILED','UNKNOWN')),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CHECK ((status='PENDING' AND started_at IS NULL AND completed_at IS NULL)
        OR (status='DISPATCHED' AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('COMPLETE','FAILED','UNKNOWN') AND started_at IS NOT NULL AND completed_at IS NOT NULL)),
    CHECK ((status IN ('PENDING','DISPATCHED') AND length(instructions) BETWEEN 1 AND 4096)
        OR (status IN ('COMPLETE','FAILED','UNKNOWN') AND instructions=''))
);
CREATE INDEX ix_prompt_generation_pending ON prompt_generation_jobs(created_at,job_id) WHERE status='PENDING';
