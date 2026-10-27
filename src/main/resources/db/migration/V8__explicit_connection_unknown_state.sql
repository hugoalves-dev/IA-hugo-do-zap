ALTER TABLE watcher_connection_state
    ADD COLUMN connection_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

UPDATE watcher_connection_state
SET connection_state = CASE
    WHEN connected IS TRUE THEN 'CONNECTED'
    WHEN outage_started_at IS NOT NULL OR manual_pairing_required IS TRUE OR alerted IS TRUE THEN 'DISCONNECTED'
    ELSE 'UNKNOWN'
END;

ALTER TABLE watcher_connection_state
    ADD CONSTRAINT ck_watcher_connection_state_value
        CHECK (connection_state IN ('UNKNOWN', 'CONNECTED', 'DISCONNECTED')),
    DROP COLUMN connected;
