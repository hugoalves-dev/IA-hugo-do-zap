-- Restrictive operational state only. Legacy configuration columns are retained as history.
create table operational_restrictions (
    singleton_id integer primary key check (singleton_id = 1),
    emergency_latched boolean not null default false,
    simulation_paused boolean not null default false,
    updated_at timestamptz not null default now()
);
-- A previously audited emergency remains restrictive even if legacy settings were
-- edited later. Recovery requires the new confirmed administrative command.
insert into operational_restrictions(singleton_id, emergency_latched)
select 1, exists(select 1 from telegram_admin_audit where action='emergencia' and outcome='APPLIED');
