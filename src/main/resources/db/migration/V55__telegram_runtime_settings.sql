CREATE TABLE telegram_runtime_settings (
    setting_key VARCHAR(100) PRIMARY KEY CHECK (setting_key IN ('ai.context_window_hours')),
    value_type VARCHAR(16) NOT NULL CHECK (value_type = 'INTEGER'),
    value_json JSONB NOT NULL CHECK (jsonb_typeof(value_json) = 'number'),
    is_overridden BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    updated_by BIGINT NOT NULL CHECK (updated_by > 0),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_context_window_value CHECK (
        setting_key <> 'ai.context_window_hours'
        OR ((value_json::TEXT)::INTEGER BETWEEN 1 AND 168)
    )
);

CREATE TABLE telegram_runtime_settings_history (
    setting_key VARCHAR(100) NOT NULL CHECK (setting_key IN ('ai.context_window_hours')),
    version BIGINT NOT NULL CHECK (version > 0),
    previous_json JSONB,
    next_json JSONB NOT NULL,
    previous_overridden BOOLEAN,
    next_overridden BOOLEAN NOT NULL,
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    changed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (setting_key, version),
    CONSTRAINT ck_runtime_history_values CHECK (
        jsonb_typeof(next_json) = 'number'
        AND (previous_json IS NULL OR jsonb_typeof(previous_json) = 'number')
    )
);
