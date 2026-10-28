-- No consent, provider credential, or budget is installed by this migration.
CREATE TABLE ai_data_consents (
    chat_jid VARCHAR(255) NOT NULL,
    situation VARCHAR(32) NOT NULL CHECK (situation IN ('CONTACT_REQUEST','GROUP_TRIGGER','CRITICAL_URGENCY')),
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('gemini','openrouter')),
    privacy_approved BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (chat_jid,situation,provider)
);
-- Once a batch may have left the process it is never automatically sent again after a crash.
-- Only bounded classification metadata, no message text or model evidence, is retained here.
CREATE TABLE simulation_ai_evaluations (
    batch_id UUID PRIMARY KEY REFERENCES simulation_batches(batch_id) ON DELETE CASCADE,
    lease_token UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    result JSONB NULL
);
