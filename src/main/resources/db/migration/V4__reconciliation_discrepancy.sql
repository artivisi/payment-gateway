-- Reconciliation outcomes: per-run counts + a discrepancy ledger.

alter table reconciliation_run
    add column matched_count     integer,
    add column recovered_count   integer,
    add column discrepancy_count integer;

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
