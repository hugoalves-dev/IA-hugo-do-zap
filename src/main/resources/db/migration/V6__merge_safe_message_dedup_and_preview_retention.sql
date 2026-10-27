ALTER TABLE processed_message
    ADD COLUMN canonical_chat_jid VARCHAR(255) NULL,
    ADD COLUMN upstream_message_key CHAR(64) NULL,
    ADD COLUMN canonical_participant_jid VARCHAR(255) NULL;

CREATE UNIQUE INDEX uq_processed_message_identity
    ON processed_message(canonical_chat_jid, upstream_message_key, canonical_participant_jid)
    WHERE upstream_message_key IS NOT NULL;

-- V4 backfilled expires_at from migration time, not the original content age.
-- Shorten those deadlines without refreshing updated_at or extending retention.
UPDATE pending_notification
SET expires_at = LEAST(expires_at, updated_at + INTERVAL '7 days');

DELETE FROM pending_notification WHERE expires_at <= CURRENT_TIMESTAMP;

UPDATE pending_notification
SET payload = jsonb_set(payload, '{previewExpiresAt}',
    to_jsonb(updated_at + INTERVAL '7 days'))
WHERE (payload->>'previewExpiresAt')::timestamptz > updated_at + INTERVAL '7 days';

ALTER TABLE pending_notification
    ADD CONSTRAINT ck_pending_notification_max_retention
        CHECK (expires_at <= updated_at + INTERVAL '7 days'),
    ADD CONSTRAINT ck_pending_preview_max_retention
        CHECK (payload->>'previewExpiresAt' IS NULL
            OR (payload->>'previewExpiresAt')::timestamptz <= updated_at + INTERVAL '7 days');
