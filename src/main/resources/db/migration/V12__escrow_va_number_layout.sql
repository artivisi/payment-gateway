-- The gateway allocates VA numbers from now on, so the whole layout has to live on the escrow.
--
-- Until now a consumer composed the number and the gateway only checked it (prefix, length,
-- numeric). That put every bank's number layout in two places at once, and a consumer could only
-- target escrows it had been configured for — which is why pay-via-any-bank could not scale past
-- the one bank each consumer happened to know about.

-- How the number is composed, and the segment widths it is composed from.
alter table escrow_account add column allocation_strategy varchar(32);
alter table escrow_account add column slot_digits         integer;
alter table escrow_account add column payer_padding       varchar(8);

-- The form a payer uses from a different bank. Composed as interbank_prefix || va_number.
--
-- Its own column rather than something derived from company_id: the two are unrelated strings at
-- the deployment this was built for, where the escrow's company id is five digits and the interbank
-- form carries a seven-digit routing prefix. An empty string is a meaningful value — it means the
-- interbank form is the VA number itself — and is set deliberately, never assumed.
alter table escrow_account add column interbank_prefix varchar(16);

-- PAYER_ENCODED is the only strategy this release implements, so setting it on existing rows states
-- a fact rather than guessing one.
update escrow_account set allocation_strategy = 'PAYER_ENCODED' where allocation_strategy is null;

-- slot_digits, payer_padding and interbank_prefix are left null on purpose.
--
-- They cannot be derived from anything in this database, and inventing them would mint numbers that
-- belong to other payers. They are also not made NOT NULL here: a migration that cannot complete
-- leaves Flyway failed and the application refusing to start in the middle of a deploy, which is a
-- worse failure than the one it prevents. Instead VaNumberAllocator refuses to allocate through an
-- escrow whose layout is incomplete, naming the escrow and the missing field, and the escrow form
-- requires all three before an escrow can be enabled. Each deployment sets them from the layout it
-- has been issuing under — see the deploy repository.

comment on column escrow_account.allocation_strategy is
    'How the gateway composes a VA number in this escrow: PAYER_ENCODED.';
comment on column escrow_account.slot_digits is
    'Width of the slot segment between the prefix and the payer segment. The payer segment takes what is left of va_digit_length.';
comment on column escrow_account.payer_padding is
    'LEFT or RIGHT zero-fill of the payer segment. RIGHT reproduces numbers already issued under that rule.';
comment on column escrow_account.interbank_prefix is
    'Prefixed to va_number to form the number a payer uses from another bank. Empty string means the interbank form is the VA number itself.';

-- The interbank form is stored with the VA, not derived at read time.
--
-- A payer is told this number once and keeps it. Deriving it from the escrow's current prefix would
-- make a later prefix change rewrite history: every past charge would report a number nobody was
-- ever given, and reconciliation against an old receipt would stop matching.
alter table virtual_account add column interbank_va_number varchar(64);

comment on column virtual_account.interbank_va_number is
    'What the payer was told to use from another bank, as composed when this VA was allocated. Null for VAs created before the gateway allocated numbers.';
