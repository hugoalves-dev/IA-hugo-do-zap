CREATE TABLE simulation_batches (
    batch_id UUID PRIMARY KEY,
    chat_jid VARCHAR(255) NOT NULL,
    participant_jid VARCHAR(255) NOT NULL DEFAULT '',
    status VARCHAR(12) NOT NULL CHECK (status IN ('OPEN','READY','CLAIMED','COMPLETED','EXPIRED')),
    first_message_at TIMESTAMPTZ NOT NULL,
    last_message_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    metadata_expires_at TIMESTAMPTZ NOT NULL,
    CHECK (first_message_at <= last_message_at),
    CHECK (expires_at <= created_at + INTERVAL '7 days'),
    CHECK (metadata_expires_at <= created_at + INTERVAL '365 days')
);
CREATE UNIQUE INDEX ux_simulation_open_group ON simulation_batches(chat_jid,participant_jid) WHERE status='OPEN';
CREATE INDEX ix_simulation_ready ON simulation_batches(status,due_at) WHERE status IN ('OPEN','READY','CLAIMED');
CREATE INDEX ix_simulation_batch_expiry ON simulation_batches(expires_at);

CREATE TABLE simulation_batch_messages (
    batch_id UUID NOT NULL REFERENCES simulation_batches(batch_id) ON DELETE CASCADE,
    chat_jid VARCHAR(255) NOT NULL,
    participant_jid VARCHAR(255) NOT NULL DEFAULT '',
    external_id VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY (batch_id,external_id),
    CONSTRAINT uq_simulation_event_dedup UNIQUE(chat_jid,participant_jid,external_id)
);
CREATE INDEX ix_simulation_messages_expiry_lookup ON simulation_batch_messages(batch_id,received_at);

CREATE TABLE simulation_decisions (
    batch_id UUID PRIMARY KEY REFERENCES simulation_batches(batch_id),
    would_alert BOOLEAN NOT NULL,
    would_reply BOOLEAN NOT NULL,
    template_id VARCHAR(120) NULL,
    decision_reason VARCHAR(120) NOT NULL,
    blocked_reason VARCHAR(120) NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    metadata_expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_simulation_decision_expiry ON simulation_decisions(metadata_expires_at);
