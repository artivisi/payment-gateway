package com.artivisi.paymentgateway.adapter.bsi;

import java.math.BigDecimal;

/** Outbound BSI message (gateway → bank). */
public record BsiResponse(
        String responseCode,
        String responseMessage,
        String action,
        String nomorPembayaran,
        String nomorInvoice,
        String jenisAkun,
        String nama,
        BigDecimal tagihanTotal,
        BigDecimal tagihanEfektif,
        BigDecimal akumulasiPembayaran,
        String referensiPembayaran,
        String idTransaksi
) {
    public static BsiResponse error(String code, String message, String action,
                                    String nomorPembayaran, String idTransaksi) {
        return new BsiResponse(code, message, action, nomorPembayaran, null, null, null,
                null, null, null, null, idTransaksi);
    }
}
