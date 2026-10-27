CREATE TABLE archive_monitor_settings (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    mode VARCHAR(32) NOT NULL CHECK (mode IN ('ALL', 'ARCHIVED_ONLY', 'UNARCHIVED_ONLY')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO archive_monitor_settings(singleton_id, mode) VALUES (1, 'ALL');
