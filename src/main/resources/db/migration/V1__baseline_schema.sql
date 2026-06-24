-- Baseline schema for the multi-bank VA payment gateway.
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
    id            varchar(36)  primary key,
    code          varchar(64)  not null unique,
    name          varchar(255) not null,
    client_id     varchar(64)  not null unique,
    client_secret text         not null,   -- encrypted at rest
    webhook_url   varchar(512) not null,
    status        varchar(20)  not null,   -- ACTIVE | INACTIVE
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null
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

create table virtual_account (
    id                varchar(36)  primary key,
    id_charge         varchar(36)  not null references charge (id),
    id_escrow_account varchar(36)  not null references escrow_account (id),
    va_number         varchar(64)  not null,
    status            varchar(20)  not null,   -- ACTIVE | PAID | CANCELLED | EXPIRED
    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,
    constraint uq_va_escrow_number unique (id_escrow_account, va_number)
);
create index idx_va_charge on virtual_account (id_charge);

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
    id                varchar(36) primary key,
    id_escrow_account varchar(36) not null references escrow_account (id),
    period            date        not null,
    status            varchar(20) not null,   -- PENDING | COMPLETED | FAILED
    started_at        timestamptz,
    finished_at       timestamptz,
    created_at        timestamptz not null
);
create index idx_recon_escrow on reconciliation_run (id_escrow_account);

create table audit_event (
    id          varchar(36) primary key,
    event_type  varchar(64) not null,
    entity_type varchar(64) not null,
    entity_id   varchar(36),
    detail      text,
    created_at  timestamptz not null
);
create index idx_audit_entity on audit_event (entity_type, entity_id);
