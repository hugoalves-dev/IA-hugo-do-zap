CREATE TABLE telegram_webhook_inbox (
    update_id BIGINT PRIMARY KEY CHECK (update_id >= 0),
    payload JSONB NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DONE','DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    last_error VARCHAR(64)
);
CREATE INDEX ix_telegram_webhook_inbox_ready
    ON telegram_webhook_inbox(available_at,update_id) WHERE status='PENDING';
CREATE INDEX ix_telegram_webhook_inbox_retention
    ON telegram_webhook_inbox(completed_at) WHERE status IN ('DONE','DEAD');
