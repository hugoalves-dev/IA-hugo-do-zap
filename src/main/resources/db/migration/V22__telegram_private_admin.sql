CREATE TABLE telegram_admin_callback_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    chat_id BIGINT NOT NULL CHECK (chat_id > 0),
    action JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL,
    CHECK (created_at < expires_at)
);

CREATE INDEX ix_telegram_admin_callback_expiry ON telegram_admin_callback_tokens(expires_at);

CREATE TABLE telegram_admin_audit (
    id BIGSERIAL PRIMARY KEY,
    action VARCHAR(40) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('APPLIED','REJECTED')),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    actor_chat_id BIGINT NOT NULL CHECK (actor_chat_id > 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_telegram_admin_audit_created ON telegram_admin_audit(created_at);

CREATE TABLE telegram_admin_settings (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    simulation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    polling_offset BIGINT NOT NULL DEFAULT 0 CHECK (polling_offset >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO telegram_admin_settings(singleton_id) VALUES (1);
