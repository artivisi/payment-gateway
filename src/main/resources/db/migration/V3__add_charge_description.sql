-- Bill description (keterangan) — shown to the payer on BANK_A inquiry/payment, carried on the charge.
-- Legacy the legacy service stored this per VA (virtual_account.description); the gateway carries it on the
-- charge (one debt), echoed into the BANK_A response's keterangan field. Nullable; omitted from the
-- BANK_A response when absent.
alter table charge add column description varchar(255);
