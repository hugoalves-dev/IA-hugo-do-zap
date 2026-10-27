-- A recreated pending row must never authorize a payload from a previous READ/archive generation.
-- Ordinary message aggregation updates retain the generation so concurrent messages remain pending.
alter table pending_notification add column generation uuid not null default gen_random_uuid();
