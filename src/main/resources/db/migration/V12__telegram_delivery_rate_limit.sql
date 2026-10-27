CREATE TABLE telegram_delivery_rate_limit (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    next_allowed_at TIMESTAMPTZ
);

INSERT INTO telegram_delivery_rate_limit(singleton_id, next_allowed_at) VALUES (1, NULL);
