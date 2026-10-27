CREATE TABLE ai_budget_limits (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    daily_limit_micros BIGINT NULL CHECK (daily_limit_micros > 0),
    monthly_limit_micros BIGINT NULL CHECK (monthly_limit_micros > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Deliberately no row or default limits: paid-provider work stays blocked until an operator configures both limits.
CREATE TABLE ai_budget_reservations (
    reservation_id UUID PRIMARY KEY,
    estimated_micros BIGINT NOT NULL CHECK (estimated_micros > 0),
    reserved_micros BIGINT NOT NULL CHECK (reserved_micros >= 0),
    actual_micros BIGINT NULL CHECK (actual_micros >= 0),
    reservation_day DATE NOT NULL,
    reservation_month DATE NOT NULL CHECK (extract(day from reservation_month) = 1),
    status VARCHAR(12) NOT NULL CHECK (status IN ('RESERVED','DISPATCHED','UNCERTAIN','SETTLED','RELEASED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'SETTLED') = (actual_micros IS NOT NULL)),
    CHECK (reservation_month = date_trunc('month', reservation_day)::date)
);

CREATE INDEX ix_ai_budget_daily_usage ON ai_budget_reservations(reservation_day) WHERE status <> 'RELEASED';
CREATE INDEX ix_ai_budget_monthly_usage ON ai_budget_reservations(reservation_month) WHERE status <> 'RELEASED';
