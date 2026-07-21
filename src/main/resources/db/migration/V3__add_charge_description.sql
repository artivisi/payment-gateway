-- Bill description (keterangan) — shown to the payer on BSI inquiry/payment, carried on the charge.
-- Legacy bsm-makara stored this per VA (virtual_account.description); the gateway carries it on the
-- charge (one debt), echoed into the BSI response's keterangan field. Nullable; omitted from the
-- BSI response when absent.
alter table charge add column description varchar(255);
