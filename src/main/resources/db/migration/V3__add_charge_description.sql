-- Bill description (keterangan) — shown to the payer on bank inquiry/payment, carried on the charge.
-- The legacy service stored this per VA (virtual_account.description); the gateway carries it on the
-- charge (one debt), echoed into the bank's response. Nullable; omitted from that response when
-- absent.
alter table charge add column description varchar(255);
