ALTER TABLE inbox_events
    DROP CONSTRAINT uq_inbox_events_source_external;

ALTER TABLE inbox_events
    ADD CONSTRAINT uq_inbox_events_source_event_external
        UNIQUE (source, event_type, external_id);
