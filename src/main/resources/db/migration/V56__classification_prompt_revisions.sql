CREATE TABLE classification_prompt_revisions (
    revision INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    prompt_body VARCHAR(4000) NOT NULL CHECK (length(trim(prompt_body)) BETWEEN 1 AND 4000),
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    author_user_id BIGINT NOT NULL CHECK (author_user_id > 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE classification_prompt_state (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    active_revision INTEGER REFERENCES classification_prompt_revisions(revision),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_by BIGINT NOT NULL DEFAULT 0 CHECK (updated_by >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO classification_prompt_state(singleton_id) VALUES (1);

CREATE TABLE classification_prompt_state_history (
    version BIGINT PRIMARY KEY CHECK (version > 0),
    previous_revision INTEGER REFERENCES classification_prompt_revisions(revision),
    next_revision INTEGER NOT NULL REFERENCES classification_prompt_revisions(revision),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    changed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE telegram_prompt_input_sessions (
    user_id BIGINT PRIMARY KEY CHECK (user_id > 0),
    chat_id BIGINT NOT NULL CHECK (chat_id > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

ALTER TABLE simulation_decisions
    ADD COLUMN prompt_revision INTEGER NOT NULL DEFAULT 0 CHECK (prompt_revision >= 0),
    ADD COLUMN prompt_hash CHAR(64) NOT NULL DEFAULT repeat('0',64) CHECK (prompt_hash ~ '^[0-9a-f]{64}$');
