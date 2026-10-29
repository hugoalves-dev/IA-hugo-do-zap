-- Restrictive operational state only. Legacy configuration columns are retained as history.
create table operational_restrictions (
    singleton_id integer primary key check (singleton_id = 1),
    emergency_latched boolean not null default false,
    simulation_paused boolean not null default false,
    updated_at timestamptz not null default now()
);
insert into operational_restrictions(singleton_id) values (1);
