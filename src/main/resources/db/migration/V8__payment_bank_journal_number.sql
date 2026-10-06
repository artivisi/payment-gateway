-- The bank's own bookkeeping reference for a payment.
--
-- A launch bank sends a core-banking journal number on every payment notification: 22 digits, which
-- decode as a six-digit journal sequence, MMDDHHMMSS, and the bank's clearing code zero-padded to
-- six. The gateway never modelled the field, so Jackson dropped it silently on every callback since
-- launch.
--
-- It matters because that bank keeps three separate numbering schemes and none of them overlap: the
-- transaction id belongs to the VA collection system (what we store as bank_reference), the journal
-- number to core banking, and the transfer number printed on the account statement to funds transfer.
-- Tested on every payment in the window where both sources survive: no journal number appears in the
-- statement and no transfer number appears in a callback. So reconciling an account statement exactly
-- is impossible today, and the journal number is the reference a bank operator can actually trace
-- when asked where a payment went.
--
-- Until now the only copy lived in the legacy service's DEBUG log, which reaches back to 19 July 2026 and
-- disappears when that service is retired.
--
-- NULL is correct and expected: every payment recorded before this column, every payment recovered
-- from a settlement file rather than a notification, and any bank whose notification carries no such
-- field. It is the bank's reference or nothing — never invented.

alter table payment add column bank_journal_number varchar(64);

-- Read when tracing one payment with the bank, so the number itself is the lookup key.
create index idx_payment_bank_journal_number on payment (bank_journal_number)
    where bank_journal_number is not null;
