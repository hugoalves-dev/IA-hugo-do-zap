-- New decisions may persist a sanitized diagnostic code.
-- Historical decisions deliberately remain NULL; do not infer causes retroactively.
ALTER TABLE simulation_decisions
    ADD COLUMN classification_diagnostic VARCHAR(120);
