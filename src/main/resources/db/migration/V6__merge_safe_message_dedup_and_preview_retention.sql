ALTER TABLE processed_message
    ADD COLUMN canonical_chat_jid VARCHAR(255) NULL,
    ADD COLUMN upstream_message_key CHAR(64) NULL,
    ADD COLUMN canonical_participant_jid VARCHAR(255) NULL;

CREATE UNIQUE INDEX uq_processed_message_identity
    ON processed_message(canonical_chat_jid, upstream_message_key, canonical_participant_jid)
    WHERE upstream_message_key IS NOT NULL;

ALTER TABLE pending_notification
    ADD CONSTRAINT ck_pending_notification_max_retention
        CHECK (expires_at <= updated_at + INTERVAL '7 days'),
    ADD CONSTRAINT ck_pending_preview_max_retention
        CHECK (payload->>'previewExpiresAt' IS NULL
            OR (payload->>'previewExpiresAt')::timestamptz <= updated_at + INTERVAL '7 days');
