ALTER TABLE telegram_webhook_inbox
    ADD COLUMN rabbit_backlog_confirmed_at timestamptz;

CREATE INDEX telegram_webhook_inbox_pending_rabbit_idx
    ON telegram_webhook_inbox (update_id)
    WHERE status = 'PENDING' AND rabbit_backlog_confirmed_at IS NULL;
