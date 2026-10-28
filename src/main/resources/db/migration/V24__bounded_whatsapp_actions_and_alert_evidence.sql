ALTER TABLE whatsapp_action_outbox
    ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE whatsapp_action_outbox
   SET expires_at=least(created_at + interval '7 days', updated_at + interval '7 days');

ALTER TABLE whatsapp_action_outbox
    ALTER COLUMN expires_at SET NOT NULL,
    ADD CONSTRAINT ck_whatsapp_action_max_retention
        CHECK (expires_at > created_at AND expires_at <= created_at + interval '7 days'),
    DROP CONSTRAINT whatsapp_action_outbox_status_check;

ALTER TABLE whatsapp_action_outbox DROP COLUMN provider_message_id;

UPDATE whatsapp_action_outbox SET status='UNKNOWN' WHERE status='UNCERTAIN';

ALTER TABLE whatsapp_action_outbox
    ADD CONSTRAINT ck_whatsapp_action_status
        CHECK (status IN ('PENDING','DISPATCHED','DELIVERED','FAILED','UNKNOWN','SUPPRESSED'));

CREATE INDEX ix_whatsapp_action_expiry ON whatsapp_action_outbox(expires_at);
DROP INDEX ux_whatsapp_action_one_active_per_chat;
CREATE UNIQUE INDEX ux_whatsapp_action_one_active_per_chat ON whatsapp_action_outbox(chat_jid)
    WHERE status IN ('PENDING','DISPATCHED','UNKNOWN');

ALTER TABLE simulation_decisions
    ADD COLUMN whatsapp_action_enqueued BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN intelligent_alert_enqueued BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN classification_status VARCHAR(12) NOT NULL DEFAULT 'UNCERTAIN'
        CHECK (classification_status IN ('CLASSIFIED','UNCERTAIN')),
    ADD COLUMN classification_urgency VARCHAR(12) NOT NULL DEFAULT 'UNCERTAIN'
        CHECK (classification_urgency IN ('LOW','NORMAL','HIGH','CRITICAL','UNCERTAIN')),
    ADD COLUMN classification_confidence DOUBLE PRECISION NOT NULL DEFAULT 0
        CHECK (classification_confidence >= 0 AND classification_confidence <= 1),
    ADD COLUMN intelligent_alert_reference_hash VARCHAR(64) NULL
        CHECK (intelligent_alert_reference_hash IS NULL OR intelligent_alert_reference_hash ~ '^[0-9a-fA-F]{64}$');

UPDATE simulation_decisions
   SET whatsapp_action_enqueued=TRUE
 WHERE batch_id IN (SELECT decision_id FROM whatsapp_action_outbox);

COMMENT ON COLUMN simulation_decisions.whatsapp_action_enqueued IS
    'Non-identifying tombstone prevents replay after action payload and recipient identifiers expire.';
COMMENT ON COLUMN simulation_decisions.intelligent_alert_reference_hash IS
    'SHA-256 of a source message external ID. Alert outbox exposes only the persisted decision UUID.';

CREATE TABLE whatsapp_owner_message_references (
    chat_jid VARCHAR(255) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (chat_jid, external_id),
    CONSTRAINT ck_whatsapp_owner_reference_retention
        CHECK (expires_at > observed_at AND expires_at <= observed_at + interval '7 days')
);
CREATE INDEX ix_whatsapp_owner_reference_expiry ON whatsapp_owner_message_references(expires_at);
