ALTER TABLE outbox_actions
    ADD COLUMN rabbit_confirmed_at timestamptz;
ALTER TABLE outbox_actions
    ADD COLUMN rabbit_ingress_at timestamptz;

CREATE INDEX outbox_actions_pending_rabbit_publish_idx
    ON outbox_actions (created_at, id)
    WHERE status = 'PENDING' AND rabbit_confirmed_at IS NULL;
