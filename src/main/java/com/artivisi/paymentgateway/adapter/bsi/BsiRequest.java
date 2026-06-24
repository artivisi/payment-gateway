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
        String kodeTerminal
) {
}
