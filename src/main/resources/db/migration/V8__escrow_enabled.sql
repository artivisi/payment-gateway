-- Disable/archive an escrow instead of hard-deleting (it has charges/VAs/payments history).
-- A disabled escrow is rejected for new charges; existing data is preserved.
alter table escrow_account add column enabled boolean not null default true;
