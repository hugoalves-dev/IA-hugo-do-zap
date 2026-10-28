-- Hashed conversation keys and UUID tombstones contain no payload or recipient address.
CREATE TABLE decision_delivery_claims (
    chat_key VARCHAR(64) PRIMARY KEY,
    decision_id UUID NOT NULL
);
CREATE INDEX ix_delivery_claim_decision ON decision_delivery_claims(decision_id);
CREATE TABLE decision_delivery_attempts (
    decision_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL CHECK(channel IN ('TELEGRAM','WHATSAPP')),
    attempt_token UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('DISPATCHED','CONFIRMED','RETRYABLE','UNKNOWN')),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(decision_id,channel)
);
-- DISPATCHED/UNKNOWN never expire into permission to resend. Reconciliation requires independent remote proof.
CREATE INDEX ix_unresolved_telegram_dispatch ON decision_delivery_attempts ((decision_id::text))
    WHERE channel='TELEGRAM' AND status IN ('DISPATCHED','UNKNOWN');
