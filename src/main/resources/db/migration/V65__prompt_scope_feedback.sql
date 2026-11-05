-- Only finite, backend-validated scope codes and the prompt digest are stored.
-- No raw admin instructions or provider content is duplicated in this table.
CREATE TABLE prompt_scope_feedback (
    draft_id UUID NOT NULL REFERENCES classification_prompt_drafts(id) ON DELETE CASCADE,
    draft_version BIGINT NOT NULL CHECK (draft_version > 0),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    prompt_hash CHAR(64) NOT NULL CHECK (prompt_hash ~ '^[0-9a-f]{64}$'),
    feedback JSONB NOT NULL CHECK (jsonb_typeof(feedback) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(draft_id, draft_version)
);
CREATE INDEX ix_prompt_scope_feedback_created ON prompt_scope_feedback(created_at);
