package com.artivisi.paymentgateway.adapter.bsi;

import java.math.BigDecimal;

/** Inbound BSI message (bank → gateway). One endpoint, dispatched on {@code action}. */
public record BsiRequest(
        String action,
        String checksum,
        String nomorPembayaran,
        String nomorInvoice,
        String idTransaksi,
        BigDecimal nilai,
        String tanggalTransaksi,
        String kodeBank,
        String kodeChannel,
        String kodeTerminal,
        /**
         * BSI's core-banking journal reference for the payment. Absent from this record until
         * 2026-08-25, so Jackson silently dropped it on every callback — and it is the only
         * identifier BSI sends that its own operators can trace against the settlement account.
         */
        String nomorJurnalPembukuan
) {
}
