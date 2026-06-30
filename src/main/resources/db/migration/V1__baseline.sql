-- Baseline schema for the multi-bank VA payment gateway (consolidated).
--
-- Escrow-centric: EscrowAccount is the core unit (one bank biller).
-- Charge is the unit of money owed, payable via 1..N sibling VirtualAccounts across escrows.

create table escrow_account (
    id                        varchar(36)   primary key,
    code                      varchar(64)   not null unique,
    provider                  varchar(32)   not null,   -- maybank | bsi | cimb
    hosting_model             varchar(20)   not null,   -- SELF_HOSTED | BANK_HOSTED
    transport                 varchar(20)   not null,   -- REST_JSON | SOAP_XML
    auth_scheme               varchar(20)   not null,   -- SNAP | PROPRIETARY
    active_environment        varchar(20)   not null,   -- SANDBOX | PRODUCTION
    enabled                   boolean       not null,
    client_id                 varchar(255),
    client_secret             text,                      -- encrypted at rest
    partner_id                varchar(255),
    channel_id                varchar(64),
    private_key               text,                      -- encrypted at rest
    public_key                text,
    sandbox_base_url          varchar(255),
    production_base_url       varchar(255),
    settlement_account_number varchar(64)   not null,
    settlement_account_name   varchar(255)  not null,
    company_id                varchar(32)   not null,
    va_prefix                 varchar(32)   not null,
    va_digit_length           integer       not null,
    merchant_tag              varchar(64),
    institution_tag           varchar(64),
    created_at                timestamptz   not null,
    updated_at                timestamptz   not null
);

create table consumer (
    id                varchar(36)  primary key,
    code              varchar(64)  not null unique,
    name              varchar(255) not null,
    client_id         varchar(64)  not null unique,
    client_secret     text         not null,   -- encrypted at rest
    webhook_url       varchar(512) not null,
    webhook_suspended boolean      not null,
    status            varchar(20)  not null,   -- ACTIVE | INACTIVE
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null
);

create table charge (
    id                 varchar(36)   primary key,
    id_consumer        varchar(36)   not null references consumer (id),
    consumer_reference varchar(128)  not null,
    payer_name         varchar(255)  not null,
    payer_email        varchar(255),
    payer_phone        varchar(32),
    charge_type        varchar(20)   not null,   -- OPEN | CLOSED | INSTALLMENT
    amount             numeric(19,2) not null,
    cumulative_paid    numeric(19,2) not null,
    status             varchar(20)   not null,   -- ACTIVE | PARTIALLY_PAID | PAID | EXPIRED | CANCELLED
    expires_at         timestamptz,
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    constraint uq_charge_consumer_reference unique (id_consumer, consumer_reference)
);
create index idx_charge_consumer on charge (id_consumer);

-- VA numbers are reusable once inactive: only one ACTIVE VA per escrow+number is allowed.
create table virtual_account (
    id                varchar(36)  primary key,
    id_charge         varchar(36)  not null references charge (id),
    id_escrow_account varchar(36)  not null references escrow_account (id),
    va_number         varchar(64)  not null,
    status            varchar(20)  not null,   -- ACTIVE | PAID | CANCELLED | EXPIRED
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null
);
create index idx_va_charge on virtual_account (id_charge);
create unique index uq_va_escrow_number_active
    on virtual_account (id_escrow_account, va_number)
    where status = 'ACTIVE';

create table payment (
    id                 varchar(36)   primary key,
    id_virtual_account varchar(36)   not null references virtual_account (id),
    id_charge          varchar(36)   not null references charge (id),
    amount             numeric(19,2) not null,
    bank_reference     varchar(128)  not null,
    transaction_time   timestamptz   not null,
    status             varchar(20)   not null,   -- ACCEPTED | REVERSED
    created_at         timestamptz   not null,
    constraint uq_payment_va_reference unique (id_virtual_account, bank_reference)
);
create index idx_payment_charge on payment (id_charge);

create table reconciliation_run (
    id                 varchar(36) primary key,
    id_escrow_account  varchar(36) not null references escrow_account (id),
    period             date        not null,
    status             varchar(20) not null,   -- PENDING | COMPLETED | FAILED
    started_at         timestamptz,
    finished_at        timestamptz,
    matched_count      integer,
    recovered_count    integer,
    discrepancy_count  integer,
    created_at         timestamptz not null
);
create index idx_recon_escrow on reconciliation_run (id_escrow_account);

create table reconciliation_discrepancy (
    id                    varchar(36)   primary key,
    id_reconciliation_run varchar(36)   not null references reconciliation_run (id),
    type                  varchar(32)   not null,
    va_number             varchar(64),
    bank_reference        varchar(128),
    amount                numeric(19,2),
    id_payment            varchar(36)   references payment (id),
    detail                varchar(512),
    created_at            timestamptz   not null
);
create index idx_recon_disc_run on reconciliation_discrepancy (id_reconciliation_run);

-- Audit log (PCI Req 10). actor is null for system/unauthenticated events.
create table audit_event (
    id          varchar(36)  primary key,
    event_type  varchar(64)  not null,
    entity_type varchar(64)  not null,
    entity_id   varchar(36),
    detail      text,
    actor       varchar(100),
    created_at  timestamptz  not null
);
create index idx_audit_entity on audit_event (entity_type, entity_id);

-- Transactional outbox for consumer webhook notifications.
-- Enqueued in the same transaction as the payment; dispatched with retry/backoff.
create table webhook_delivery (
    id                 varchar(36)  primary key,
    id_consumer        varchar(36)  not null references consumer (id),
    id_charge          varchar(36)  not null references charge (id),
    id_payment         varchar(36)  references payment (id),
    event_type         varchar(40)  not null,   -- PAYMENT_RECEIVED | CHARGE_PAID
    target_url         varchar(512) not null,
    payload            text         not null,
    status             varchar(20)  not null,   -- PENDING | DELIVERED | FAILED
    attempts           integer      not null,
    max_attempts       integer      not null,
    next_attempt_at    timestamptz  not null,
    last_response_code integer,
    last_error         varchar(512),
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null
);
create index idx_webhook_due on webhook_delivery (status, next_attempt_at);
create index idx_webhook_charge on webhook_delivery (id_charge);

-- SNAP adapter: issued access tokens and per-day X-EXTERNAL-ID idempotency.
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

-- App-layer IP allowlist for bank-callback endpoints (PCI Req 7). No enabled rule for a
-- provider = unrestricted. CIDR validated in the application before persistence.
create table bank_ip_rule (
    id         varchar(36)  primary key,
    provider   varchar(32)  not null,   -- bsi | cimb | maybank
    cidr       varchar(64)  not null,
    label      varchar(200),
    enabled    boolean      not null,
    created_at timestamptz  not null,
    updated_at timestamptz  not null
);
create index idx_bank_ip_rule_provider on bank_ip_rule (provider);

-- Data-driven RBAC: roles bundle permissions; each operator has exactly one role.
-- Permissions are a fixed code vocabulary (enforced in SecurityConfig).
create table role (
    id              varchar(36)  primary key,
    name            varchar(40)  not null unique,
    label           varchar(120),
    built_in        boolean      not null,   -- cannot be deleted
    all_permissions boolean      not null,   -- superuser: every permission incl. future
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null
);

create table role_permission (
    role_id    varchar(36) not null references role (id) on delete cascade,
    permission varchar(40) not null,
    primary key (role_id, permission)
);

-- Admin-UI operator accounts (PCI Req 7/8). role_id is a FK to the role table.
create table operator (
    id                   varchar(36)  primary key,
    username             varchar(100) not null unique,
    password_hash        varchar(100) not null,   -- bcrypt
    full_name            varchar(200),
    role_id              varchar(36)  not null references role (id),
    enabled              boolean      not null,
    failed_attempts      integer      not null,
    locked_until         timestamptz,
    mfa_secret           text,                     -- base32 TOTP secret, encrypted at rest
    mfa_enabled          boolean      not null,
    must_change_password boolean      not null,
    last_login_at        timestamptz,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null
);
