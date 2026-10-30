-- Only short-lived owner messages are captured for interpretation of new inbound
-- messages. No historical backfill, no automatic message sending.
CREATE TABLE simulation_owner_context (
    chat_jid VARCHAR(255) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    text TEXT NOT NULL CHECK (length(text) <= 1000),
    PRIMARY KEY (chat_jid, external_id)
);

CREATE INDEX simulation_owner_context_recent_idx
    ON simulation_owner_context (chat_jid, received_at DESC);

CREATE INDEX simulation_owner_context_expiration_idx
    ON simulation_owner_context (expires_at);
