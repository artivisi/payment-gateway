-- The INSTALLMENT charge type and the PARTIALLY_PAID charge status are gone from the code.
-- Instalments are the consumer's concern: the receivables application holds the schedule and
-- reprices one CLOSED charge over time, so the gateway never needed a type for them, and no
-- production charge ever carried one. No data changes; this migration only refuses to run on a
-- database where that assumption does not hold, so the enum removal can never orphan a row silently.
do $$
declare n bigint;
begin
    select count(*) into n from charge where charge_type = 'INSTALLMENT' or status = 'PARTIALLY_PAID';
    if n > 0 then
        raise exception 'V10: % charge row(s) still carry INSTALLMENT or PARTIALLY_PAID; migrate them before removing the code', n;
    end if;
end $$;
