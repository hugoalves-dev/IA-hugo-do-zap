ALTER TABLE classification_prompt_revisions
    DROP CONSTRAINT classification_prompt_revisions_prompt_body_check,
    ALTER COLUMN prompt_body TYPE VARCHAR(16000),
    ADD CONSTRAINT classification_prompt_revisions_prompt_body_check CHECK (length(trim(prompt_body)) BETWEEN 1 AND 16000),
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'manual' CHECK (origin IN ('manual', 'ai_assisted')),
    ADD COLUMN description VARCHAR(500) NOT NULL DEFAULT '',
    ADD CONSTRAINT classification_prompt_revision_hash_check CHECK (sha256 = encode(sha256(convert_to(prompt_body, 'UTF8')), 'hex')) NOT VALID;

CREATE FUNCTION reject_classification_prompt_revision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'classification prompt revisions are immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER classification_prompt_revision_immutable BEFORE UPDATE OR DELETE ON classification_prompt_revisions
    FOR EACH ROW EXECUTE FUNCTION reject_classification_prompt_revision_mutation();

CREATE TABLE classification_prompt_drafts (
    id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    base_revision INTEGER REFERENCES classification_prompt_revisions(revision),
    body TEXT NOT NULL CHECK (char_length(body) <= 16000),
    origin VARCHAR(16) NOT NULL CHECK (origin IN ('manual', 'ai_assisted')),
    description VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL CHECK (status IN ('editing','preview','cancelled','activated')),
    refinement_count INTEGER NOT NULL DEFAULT 0 CHECK (refinement_count BETWEEN 0 AND 3),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX classification_prompt_drafts_owner ON classification_prompt_drafts(owner_id, expires_at);
ALTER TABLE classification_prompt_state_history ALTER COLUMN next_revision DROP NOT NULL;
ALTER TABLE classification_prompt_state_history ADD COLUMN event VARCHAR(16) NOT NULL DEFAULT 'activate'
    CHECK (event IN ('activate', 'restore', 'reset'));
ALTER TABLE classification_prompt_state_history ADD CONSTRAINT classification_prompt_history_event_target_check
    CHECK ((event = 'reset' AND next_revision IS NULL) OR (event IN ('activate','restore') AND next_revision IS NOT NULL));
