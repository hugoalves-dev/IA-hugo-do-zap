ALTER TABLE outbox_actions
    ADD COLUMN pending_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN lease_token UUID NULL,
    ADD COLUMN lease_expires_at TIMESTAMPTZ NULL,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN sensitive_expires_at TIMESTAMPTZ NULL,
    ADD COLUMN last_error VARCHAR(100) NULL;

UPDATE outbox_actions
SET sensitive_expires_at = LEAST(created_at + INTERVAL '7 days', now() + INTERVAL '7 days')
WHERE sensitive_expires_at IS NULL;

ALTER TABLE outbox_actions
    ALTER COLUMN sensitive_expires_at SET NOT NULL,
    ADD CONSTRAINT ck_outbox_actions_snapshot_array CHECK (jsonb_typeof(pending_snapshot) = 'array'),
    ADD CONSTRAINT ck_outbox_actions_max_retention CHECK (sensitive_expires_at <= created_at + INTERVAL '7 days');

CREATE INDEX ix_outbox_actions_claim
    ON outbox_actions (status, available_at, lease_expires_at, id);
CREATE INDEX ix_outbox_actions_sensitive_expiry
    ON outbox_actions (sensitive_expires_at);

CREATE TABLE watcher_connection_state (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    connected BOOLEAN NOT NULL DEFAULT FALSE,
    outage_generation BIGINT NOT NULL DEFAULT 0 CHECK (outage_generation >= 0),
    outage_started_at TIMESTAMPTZ NULL,
    reason VARCHAR(120) NULL,
    status_code INTEGER NULL,
    manual_pairing_required BOOLEAN NOT NULL DEFAULT FALSE,
    alerted BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO watcher_connection_state(singleton_id) VALUES (1);
