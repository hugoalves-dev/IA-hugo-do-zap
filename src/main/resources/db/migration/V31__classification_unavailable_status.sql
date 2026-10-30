ALTER TABLE simulation_decisions DROP CONSTRAINT IF EXISTS simulation_decisions_classification_status_check;
ALTER TABLE simulation_decisions ADD CONSTRAINT simulation_decisions_classification_status_check
    CHECK (classification_status IN ('CLASSIFIED', 'UNCERTAIN', 'UNAVAILABLE'));
