-- Confirmed delivery cooldown is shared by conversation; the originating decision's sibling channel remains eligible.
ALTER TABLE decision_cooldowns ADD COLUMN last_real_decision_id UUID NULL;
COMMENT ON COLUMN decision_cooldowns.last_real_decision_id IS
    'Non-identifying cause of last confirmed real delivery. Same-decision siblings bypass only the cooldown gate.';
