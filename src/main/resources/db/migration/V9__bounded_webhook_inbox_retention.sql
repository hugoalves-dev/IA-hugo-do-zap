ALTER TABLE inbox_events
    ADD COLUMN expires_at TIMESTAMPTZ NULL;

UPDATE inbox_events
   SET expires_at = received_at + INTERVAL '6 days 23 hours 58 minutes';

ALTER TABLE inbox_events
    ALTER COLUMN expires_at SET DEFAULT (now() + INTERVAL '6 days 23 hours 58 minutes'),
    ALTER COLUMN expires_at SET NOT NULL,
    ADD CONSTRAINT ck_inbox_events_max_retention
        CHECK (expires_at <= received_at + INTERVAL '7 days');

CREATE INDEX ix_inbox_events_expiry ON inbox_events (expires_at);
