CREATE TABLE automation_settings (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    ai_classification_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    intelligent_alerts_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    auto_reply_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    kill_switch_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    simulation_cooldown_seconds INTEGER NOT NULL DEFAULT 300 CHECK (simulation_cooldown_seconds BETWEEN 0 AND 86400),
    real_cooldown_seconds INTEGER NOT NULL DEFAULT 3600 CHECK (real_cooldown_seconds BETWEEN 0 AND 86400),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO automation_settings(singleton_id) VALUES (1);

CREATE TABLE response_templates (
    template_id VARCHAR(100) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    situation VARCHAR(32) NOT NULL CHECK (situation IN ('CONTACT_REQUEST','GROUP_TRIGGER','CRITICAL_URGENCY')),
    body TEXT NOT NULL CHECK (length(trim(body)) > 0),
    identifies_automation BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (template_id, version),
    UNIQUE (template_id, version, situation)
);
INSERT INTO response_templates(template_id,version,situation,body,identifies_automation,enabled) VALUES
('contact_request',1,'CONTACT_REQUEST','Esta é uma resposta automática. Vou retornar assim que possível.',true,true),
('group_trigger',1,'GROUP_TRIGGER','Esta é uma resposta automática. Vou retornar assim que possível.',true,true),
('critical_urgency',1,'CRITICAL_URGENCY','Alerta automático de urgência crítica.',true,true);

CREATE TABLE chat_automation_authorizations (
    chat_jid VARCHAR(255) NOT NULL,
    situation VARCHAR(32) NOT NULL CHECK (situation IN ('CONTACT_REQUEST','GROUP_TRIGGER','CRITICAL_URGENCY')),
    template_id VARCHAR(100) NOT NULL,
    template_version INTEGER NOT NULL,
    authorized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(chat_jid,situation,template_id,template_version),
    FOREIGN KEY(template_id,template_version,situation) REFERENCES response_templates(template_id,version,situation)
);

CREATE TABLE decision_cooldowns (
    chat_jid VARCHAR(255) NOT NULL,
    participant_jid VARCHAR(255) NOT NULL DEFAULT '',
    last_simulation_action_at TIMESTAMPTZ NULL,
    last_real_action_at TIMESTAMPTZ NULL,
    PRIMARY KEY(chat_jid,participant_jid)
);

ALTER TABLE simulation_decisions
    ADD COLUMN real_action_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN real_blocked_reason VARCHAR(120) NULL,
    ADD COLUMN decision_state VARCHAR(16) NOT NULL DEFAULT 'NO_ACTION'
        CHECK (decision_state IN ('WOULD_ACT','NO_ACTION','BLOCKED'));
