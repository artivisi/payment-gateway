-- SNAP adapter support: issued access tokens and per-day X-EXTERNAL-ID idempotency.

create table snap_access_token (
    id                varchar(36) primary key,
    id_escrow_account varchar(36) not null references escrow_account (id),
    access_token      varchar(64) not null unique,
    expires_at        timestamptz not null,
    created_at        timestamptz not null
);
create index idx_snap_token_escrow on snap_access_token (id_escrow_account);

create table snap_external_id (
    id                varchar(36) primary key,
    id_escrow_account varchar(36) not null references escrow_account (id),
    external_id       varchar(64) not null,
    service_name      varchar(32) not null,   -- inquiry | payment
    transaction_date  date        not null,
    created_at        timestamptz not null,
    constraint uq_snap_external_id unique (id_escrow_account, transaction_date, service_name, external_id)
);
