-- VA numbers are reusable once inactive (PAID/CANCELLED/EXPIRED).
-- Replace the all-status unique constraint with a partial index on ACTIVE only.
alter table virtual_account drop constraint uq_va_escrow_number;

create unique index uq_va_escrow_number_active
    on virtual_account (id_escrow_account, va_number)
    where status = 'ACTIVE';
