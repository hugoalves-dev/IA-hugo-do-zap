ALTER TABLE ai_budget_reservations
    DROP CONSTRAINT ai_budget_reservations_status_check;

ALTER TABLE ai_budget_reservations
    ADD CONSTRAINT ck_ai_budget_reservations_status
    CHECK (status IN ('RESERVED','DISPATCHED','UNCERTAIN','SETTLED','RELEASED'));
