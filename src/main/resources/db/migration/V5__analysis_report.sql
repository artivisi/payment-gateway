-- Operator-supplied analysis reports: computed OUTSIDE the gateway (the data may live in a legacy
-- system, a warehouse, or anywhere else), posted in, stored, and rendered here.
--
-- Kept as a series, not a single current value: these are run periodically (monthly/quarterly), and
-- the point of a collection-aging study is how the curve moves between runs.
create table analysis_report (
    id           varchar(36)  not null primary key,
    kind         varchar(64)  not null,      -- e.g. collection-aging
    title        varchar(255) not null,
    source       varchar(255) not null,      -- where the numbers came from, in the author's words
    period_label varchar(64),                -- what the run covers, e.g. "2026-Q3"
    generated_at timestamptz  not null,      -- when the analysis ran, not when it was uploaded
    payload      text         not null,      -- the report body, schema-versioned by `kind`
    created_at   timestamptz  not null default now()
);

create index idx_analysis_report_kind_generated on analysis_report (kind, generated_at desc);

-- Read-only operators should see these; ADMIN has all_permissions and needs no row.
insert into role_permission (role_id, permission)
select r.id, 'ANALYSIS_VIEW' from role r where r.name in ('AUDITOR', 'OPERATOR')
  and not exists (select 1 from role_permission rp where rp.role_id = r.id and rp.permission = 'ANALYSIS_VIEW');
