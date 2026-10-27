ALTER TABLE processed_message ADD COLUMN shadow_retry_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity';
CREATE INDEX ix_processed_shadow_retry ON processed_message(shadow_retry_at,id) WHERE shadow_payload IS NOT NULL;
