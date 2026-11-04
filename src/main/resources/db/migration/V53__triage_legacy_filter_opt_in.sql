CREATE TABLE triage_legacy_filter_opt_ins (
    chat_jid VARCHAR(255) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_triage_legacy_filter_chat CHECK (chat_jid LIKE '%@%')
);

CREATE INDEX ix_triage_legacy_filter_enabled
    ON triage_legacy_filter_opt_ins (updated_at)
    WHERE enabled;
