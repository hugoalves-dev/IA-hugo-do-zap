ALTER TABLE simulation_decisions
    ADD COLUMN triage_outcome VARCHAR(32) NOT NULL DEFAULT 'UNDECIDED',
    ADD COLUMN notification_recommended BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN reply_recommended BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN triage_reason VARCHAR(120),
    ADD COLUMN triage_blockers JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_simulation_triage_outcome
        CHECK (triage_outcome IN ('IGNORE', 'NOTIFY_ONLY', 'NOTIFY_AND_REPLY', 'UNDECIDED')),
    ADD CONSTRAINT ck_simulation_reply_requires_notification
        CHECK (NOT reply_recommended OR notification_recommended),
    ADD CONSTRAINT ck_simulation_triage_blockers
        CHECK (jsonb_typeof(triage_blockers) = 'array' AND jsonb_array_length(triage_blockers) <= 8);
