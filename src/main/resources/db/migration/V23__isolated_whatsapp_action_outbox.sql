CREATE TABLE whatsapp_action_outbox (
    action_id UUID PRIMARY KEY,
    decision_id UUID NOT NULL UNIQUE REFERENCES simulation_decisions(batch_id),
    chat_jid VARCHAR(255) NOT NULL,
    participant_jid VARCHAR(255) NOT NULL DEFAULT '',
    situation VARCHAR(32) NOT NULL CHECK (situation IN ('CONTACT_REQUEST','GROUP_TRIGGER','CRITICAL_URGENCY')),
    template_id VARCHAR(100) NOT NULL,
    template_version INTEGER NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING','DISPATCHED','DELIVERED','FAILED','UNCERTAIN','SUPPRESSED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    provider_message_id VARCHAR(255) NULL,
    last_error VARCHAR(100) NULL,
    FOREIGN KEY (template_id,template_version,situation)
        REFERENCES response_templates(template_id,version,situation)
);

CREATE INDEX ix_whatsapp_action_pending ON whatsapp_action_outbox(created_at) WHERE status='PENDING';
CREATE UNIQUE INDEX ux_whatsapp_action_one_active_per_chat ON whatsapp_action_outbox(chat_jid)
    WHERE status IN ('PENDING','DISPATCHED','UNCERTAIN');
COMMENT ON TABLE whatsapp_action_outbox IS
    'Isolated Evolution action queue. DISPATCHED and UNCERTAIN are never automatically retried; operator reconciliation is required.';
