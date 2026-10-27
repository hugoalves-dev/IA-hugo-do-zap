CREATE TABLE global_policy (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    fields JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO global_policy(singleton_id, fields) VALUES (1, '{"includeOptionalHolidays":false}'::jsonb);

CREATE TABLE policy_profiles (
    profile_name VARCHAR(80) PRIMARY KEY,
    fields JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO policy_profiles(profile_name, fields) VALUES
    ('WORK_ONLY', '{"mode":"WORK_WINDOW","workDays":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],"windows":[{"start":"08:00","end":"12:00"},{"start":"13:30","end":"17:30"}],"includeOptionalHolidays":false}'::jsonb),
    ('ANYTIME', '{"mode":"ANYTIME","includeOptionalHolidays":false}'::jsonb);

CREATE TABLE chat_policy_overrides (
    chat_jid VARCHAR(255) PRIMARY KEY,
    profile_name VARCHAR(80) NOT NULL REFERENCES policy_profiles(profile_name),
    fields JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE availability_overrides (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    state VARCHAR(20) NOT NULL CHECK (state IN ('AVAILABLE', 'UNAVAILABLE')),
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (starts_at < expires_at)
);

CREATE TABLE holiday_calendar (
    local_date DATE NOT NULL,
    jurisdiction VARCHAR(12) NOT NULL CHECK (jurisdiction IN ('BR', 'SP', 'LINS')),
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    source TEXT NULL,
    calendar_year INTEGER NOT NULL,
    validation_status VARCHAR(16) NOT NULL CHECK (validation_status IN ('VALIDATED', 'UNKNOWN', 'INVALID')),
    optional BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (local_date, jurisdiction, name),
    CHECK (calendar_year BETWEEN 2000 AND 2200),
    CHECK (validation_status <> 'VALIDATED' OR (source IS NOT NULL AND length(trim(source)) > 0 AND calendar_year = EXTRACT(YEAR FROM local_date)::INTEGER)
));

CREATE INDEX ix_holiday_calendar_year_date ON holiday_calendar(calendar_year, local_date);
