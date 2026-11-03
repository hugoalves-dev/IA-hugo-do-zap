ALTER TABLE telegram_webhook_inbox
    ADD COLUMN callback_id text,
    ADD COLUMN callback_feedback text,
    ADD COLUMN callback_answered_at timestamptz;
