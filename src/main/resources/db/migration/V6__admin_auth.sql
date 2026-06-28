-- Admin-UI access control: operator accounts (PCI Req 7/8), app-layer IP allowlist, audit actor.

create table operator (
    id                   varchar(36)  primary key,
    username             varchar(100) not null unique,
    password_hash        varchar(100) not null,            -- bcrypt
    full_name            varchar(200),
    role                 varchar(20)  not null,            -- ADMIN | OPERATOR | AUDITOR
    enabled              boolean      not null default true,
    failed_attempts      integer      not null default 0,
    locked_until         timestamptz,
    mfa_secret           text,                              -- base32 TOTP secret, encrypted at rest
    mfa_enabled          boolean      not null default false,
    must_change_password boolean      not null default false,
    last_login_at        timestamptz,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null
);

-- App-layer IP allowlist for bank-callback endpoints, per provider (bsi | cimb | maybank).
-- These endpoints are bank-driven and unauthenticated (CIMB) or integrity-only (BSI); this replaces
-- network-layer IP filtering with app-managed rules. No enabled rule for a provider = unrestricted
-- (matches prior behaviour). CIDR is validated in the application before persistence.
create table bank_ip_rule (
    id         varchar(36)  primary key,
    provider   varchar(32)  not null,            -- bsi | cimb | maybank
    cidr       varchar(64)  not null,
    label      varchar(200),
    enabled    boolean      not null default true,
    created_at timestamptz  not null,
    updated_at timestamptz  not null
);
create index idx_bank_ip_rule_provider on bank_ip_rule (provider);

-- Who performed an audited action (PCI Req 10). Null for system/unauthenticated events.
alter table audit_event add column actor varchar(100);
