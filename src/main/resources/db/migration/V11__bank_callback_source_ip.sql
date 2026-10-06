-- Where each bank message came from, kept as long as the message itself. Until now the only record
-- of an accepted callback's source address was the nginx access log, which rotates after 14 days:
-- "did this payment really come from the bank's host?" became unanswerable two weeks after it
-- arrived. 45 characters holds any IPv6 text form, including an IPv4-mapped one.
alter table bank_callback add column source_ip varchar(45);

-- Required for every row from now on. NOT VALID because rows recorded before this migration have no
-- address and nothing to backfill one from; they stay null, which reads truthfully as "not captured".
alter table bank_callback add constraint ck_bank_callback_source_ip
    check (source_ip is not null) not valid;
