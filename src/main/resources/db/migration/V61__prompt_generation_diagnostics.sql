ALTER TABLE prompt_generation_jobs
    ADD COLUMN failure_stage VARCHAR(16),
    ADD COLUMN failure_code VARCHAR(32),
    ADD COLUMN result_delivery_status VARCHAR(16) NOT NULL DEFAULT 'NOT_ENQUEUED',
    ADD COLUMN result_delivery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN result_delivery_last_error VARCHAR(100),
    ADD COLUMN result_delivery_rabbit_confirmed_at TIMESTAMPTZ,
    ADD COLUMN result_delivery_expires_at TIMESTAMPTZ;

ALTER TABLE prompt_generation_jobs
    ADD CONSTRAINT ck_prompt_generation_failure_pair
        CHECK ((failure_stage IS NULL) = (failure_code IS NULL)),
    ADD CONSTRAINT ck_prompt_generation_failure_stage
        CHECK (failure_stage IS NULL OR failure_stage IN
            ('CLAIM','PREFLIGHT','PROVIDER','VALIDATE','PERSIST','OUTBOX','DELIVERY')),
    ADD CONSTRAINT ck_prompt_generation_failure_code
        CHECK (failure_code IS NULL OR failure_code IN (
            'CONFIG_INVALID','BUDGET_MODE_BLOCKED','BUDGET_EXHAUSTED','SHADOW_PAUSED',
            'DRAFT_UNAVAILABLE','DRAFT_EXPIRED','DRAFT_CONFLICT','REFINEMENT_LIMIT',
            'INVALID_INSTRUCTIONS','PROVIDER_FAILURE','INVALID_PROVIDER_OUTPUT','PROPOSAL_REJECTED',
            'PERSISTENCE_FAILURE','RESULT_ENQUEUE_FAILED','WORKER_INTERRUPTED','CLAIM_FAILURE')),
    ADD CONSTRAINT ck_prompt_generation_failure_status
        CHECK (failure_stage IS NULL
            OR status IN ('FAILED','UNKNOWN')
            OR (status='DISPATCHED' AND failure_stage='OUTBOX' AND failure_code='RESULT_ENQUEUE_FAILED')),
    ADD CONSTRAINT ck_prompt_generation_result_delivery_status
        CHECK (result_delivery_status IN
            ('NOT_ENQUEUED','PENDING','PROCESSING','ACKNOWLEDGED','CANCELLED','EXPIRED')),
    ADD CONSTRAINT ck_prompt_generation_result_delivery_attempts
        CHECK (result_delivery_attempts >= 0);

ALTER TABLE telegram_admin_audit
    DROP CONSTRAINT telegram_admin_audit_outcome_check,
    ADD CONSTRAINT ck_telegram_admin_audit_outcome
        CHECK (outcome IN ('APPLIED','REJECTED','PROPOSED'));
