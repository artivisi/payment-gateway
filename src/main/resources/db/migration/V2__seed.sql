-- Built-in roles seed. ADMIN is a superuser (all_permissions = true), so it needs no explicit
-- role_permission rows; its effective permissions cover every code including future additions.
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
