-- An action is a bounded evidence snapshot, with its own seven-day deadline.
-- Decision audit retention may be shorter. Keep the original opaque decision UUID
-- for reconciliation without retaining the decision, batch, or sensitive input.
-- Enqueue still requires a persisted authorized decision. Dispatch revalidation
-- fails closed if that decision has expired; this never permits a resend.
ALTER TABLE whatsapp_action_outbox
    DROP CONSTRAINT whatsapp_action_outbox_decision_id_fkey;

COMMENT ON COLUMN whatsapp_action_outbox.decision_id IS
    'Original opaque decision UUID; independent action evidence survives shorter audit TTL. Missing decision blocks dispatch.';
