-- Data-driven RBAC: roles bundle permissions; each operator has exactly one role.
-- Permissions are a fixed code vocabulary (enforced in SecurityConfig); roles + assignments are data.

create table role (
    id              varchar(36)  primary key,
    name            varchar(40)  not null unique,
    label           varchar(120),
    built_in        boolean      not null default false,   -- cannot be deleted
    all_permissions boolean      not null default false,   -- superuser: every permission incl. future
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null
);

create table role_permission (
    role_id    varchar(36) not null references role (id) on delete cascade,
    permission varchar(40) not null,
    primary key (role_id, permission)
);

-- Built-in roles. ADMIN is a superuser (effective permissions = all), so it needs no explicit rows.
insert into role (id, name, label, built_in, all_permissions, created_at, updated_at) values
    ('00000000-0000-0000-0000-000000000001', 'ADMIN',    'Administrator', true, true,  now(), now()),
    ('00000000-0000-0000-0000-000000000002', 'OPERATOR', 'Operator',      true, false, now(), now()),
    ('00000000-0000-0000-0000-000000000003', 'AUDITOR',  'Auditor',       true, false, now(), now());

insert into role_permission (role_id, permission) values
    ('00000000-0000-0000-0000-000000000002', 'ESCROW_VIEW'),
    ('00000000-0000-0000-0000-000000000002', 'CONSUMER_VIEW'),
    ('00000000-0000-0000-0000-000000000002', 'CONSUMER_MANAGE'),
    ('00000000-0000-0000-0000-000000000002', 'CHARGE_VIEW'),
    ('00000000-0000-0000-0000-000000000002', 'PAYMENT_VIEW'),
    ('00000000-0000-0000-0000-000000000002', 'RECONCILIATION_VIEW'),
    ('00000000-0000-0000-0000-000000000002', 'WEBHOOK_VIEW'),
    ('00000000-0000-0000-0000-000000000002', 'WEBHOOK_MANAGE'),
    ('00000000-0000-0000-0000-000000000002', 'AUDIT_VIEW'),
    ('00000000-0000-0000-0000-000000000003', 'ESCROW_VIEW'),
    ('00000000-0000-0000-0000-000000000003', 'CONSUMER_VIEW'),
    ('00000000-0000-0000-0000-000000000003', 'CHARGE_VIEW'),
    ('00000000-0000-0000-0000-000000000003', 'PAYMENT_VIEW'),
    ('00000000-0000-0000-0000-000000000003', 'RECONCILIATION_VIEW'),
    ('00000000-0000-0000-0000-000000000003', 'WEBHOOK_VIEW'),
    ('00000000-0000-0000-0000-000000000003', 'AUDIT_VIEW');

-- Migrate operator.role (enum string) to a FK; backfill existing rows by name, then drop the column.
alter table operator add column role_id varchar(36) references role (id);
update operator set role_id = (select id from role where role.name = operator.role);
alter table operator alter column role_id set not null;
alter table operator drop column role;
