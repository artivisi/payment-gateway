-- Every inbound bank message, as the bank sent it.
--
-- Exists because a typed DTO throws away what it does not model, silently. BSI sent
-- `nomorJurnalPembukuan` on every payment notification since launch; BsiRequest had no such
-- component, Jackson ignored it by default, and the field left no trace anywhere in this system. It
-- surfaced on 2026-08-25 only because the legacy service — the legacy service being retired — happened to log
-- raw request text at DEBUG. That log reaches back five weeks and dies with the service.
--
-- Stored as data rather than left to the log, because the legacy service demonstrates both halves of the
-- lesson: it saved the investigation, and it could only save five weeks of it. Its own
-- payment_request/payment_response tables, built for exactly this, were never written to.
--
-- `checksum` is REDACTED before the row is written. It is derived from the escrow's shared key, and
-- the house rule is that signatures are never logged — a raw-capture table is the easiest place to
-- break that rule by accident.
--
-- `unknown_fields` is the point of the exercise: what the bank sent that we do not model, recorded as
-- data so it can be queried rather than found by reading logs a year later.

create table bank_callback (
    id             varchar(36) primary key,
    provider       varchar(64)  not null,
    received_at    timestamptz  not null,
    action         varchar(32),
    va_number      varchar(64),
    bank_reference varchar(64),
    payload        text         not null,
    unknown_fields varchar(512)
);

create index idx_bank_callback_received_at on bank_callback (received_at desc);
create index idx_bank_callback_bank_reference on bank_callback (bank_reference)
    where bank_reference is not null;
-- Read as "has any bank started sending us something new?", which is the question that matters.
create index idx_bank_callback_unknown_fields on bank_callback (unknown_fields)
    where unknown_fields is not null;
