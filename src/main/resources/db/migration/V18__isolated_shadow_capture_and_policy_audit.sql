ALTER TABLE processed_message ADD COLUMN shadow_payload JSONB NULL,
    ADD COLUMN shadow_expires_at TIMESTAMPTZ NULL;
CREATE INDEX ix_processed_shadow_pending ON processed_message(id) WHERE shadow_payload IS NOT NULL;
ALTER TABLE simulation_decisions ADD COLUMN policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN availability_state VARCHAR(20) NULL,
    ADD COLUMN availability_source VARCHAR(40) NULL,
    ADD COLUMN availability_reason TEXT NULL;
