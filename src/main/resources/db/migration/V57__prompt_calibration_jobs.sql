CREATE TABLE prompt_calibration_runs (
    run_id UUID PRIMARY KEY,
    prompt_revision INTEGER NOT NULL CHECK (prompt_revision >= 0),
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    chat_id BIGINT NOT NULL CHECK (chat_id > 0),
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING','DISPATCHED','COMPLETE','FAILED','UNKNOWN')),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    result_summary VARCHAR(3000),
    CHECK ((status IN ('PENDING') AND started_at IS NULL AND completed_at IS NULL)
        OR (status IN ('DISPATCHED') AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('COMPLETE','FAILED','UNKNOWN') AND started_at IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX ix_prompt_calibration_pending ON prompt_calibration_runs(created_at,run_id) WHERE status='PENDING';
CREATE INDEX ix_prompt_calibration_actor_rate ON prompt_calibration_runs(actor_user_id,created_at);

CREATE FUNCTION reject_immutable_prompt_history_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'immutable prompt history cannot be changed';
END;
$$;

CREATE TRIGGER trg_classification_prompt_revisions_immutable
    BEFORE UPDATE OR DELETE ON classification_prompt_revisions
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_prompt_history_mutation();
CREATE TRIGGER trg_classification_prompt_history_immutable
    BEFORE UPDATE OR DELETE ON classification_prompt_state_history
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_prompt_history_mutation();
CREATE TRIGGER trg_runtime_settings_history_immutable
    BEFORE UPDATE OR DELETE ON telegram_runtime_settings_history
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_prompt_history_mutation();
