-- Issue #66: the WhatsApp auto-reply cooldown no longer suppresses the owner's Telegram alert.
-- Telegram alerts get their own per-conversation minimum interval; excess alerts are deferred, never dropped.
ALTER TABLE automation_settings
    ADD COLUMN alert_min_interval_seconds INTEGER NOT NULL DEFAULT 300
        CHECK (alert_min_interval_seconds BETWEEN 0 AND 86400);

ALTER TABLE decision_cooldowns
    ADD COLUMN last_alert_at TIMESTAMPTZ NULL,
    ADD COLUMN last_alert_decision_id UUID NULL;

COMMENT ON COLUMN decision_cooldowns.last_real_action_at IS
    'Reply cooldown: confirmed real WhatsApp send only. Rows written before V66 may originate from a confirmed Telegram alert; they keep blocking automatic replies until they expire naturally (fail-closed transition).';
COMMENT ON COLUMN decision_cooldowns.last_alert_at IS
    'Confirmed intelligent Telegram alert. Rate-limits further alerts for the conversation; never blocks or starts the WhatsApp reply cooldown.';
COMMENT ON COLUMN decision_cooldowns.last_alert_decision_id IS
    'Non-identifying cause of the last confirmed Telegram alert.';
COMMENT ON COLUMN automation_settings.alert_min_interval_seconds IS
    'Minimum spacing between intelligent Telegram alerts of different decisions in the same conversation. Deferred alerts are retried, not suppressed.';
