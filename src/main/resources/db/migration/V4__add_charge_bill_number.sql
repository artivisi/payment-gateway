-- Human-meaningful bill/invoice number for finance traceability, shown to the payer as the BSI
-- nomorInvoice. Distinct from consumer_reference (the consumer's idempotency key, which may be a UUID).
-- Backfilled from legacy virtual_account.invoice_number for migrated charges; supplied by the consumer
-- on create for new charges. Nullable; omitted from the BSI response when absent (no fallback).
alter table charge add column bill_number varchar(128);
