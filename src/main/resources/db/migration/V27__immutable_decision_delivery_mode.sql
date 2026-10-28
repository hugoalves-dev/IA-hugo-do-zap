-- Historical and shadow audits never become delivery authorizations after a restart/config change.
ALTER TABLE simulation_decisions ADD COLUMN production_mode BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN alert_template_reference VARCHAR(120) NULL;
COMMENT ON COLUMN simulation_decisions.production_mode IS
    'Opt-in execution mode captured at decision creation. False audits cannot be promoted by replay.';
