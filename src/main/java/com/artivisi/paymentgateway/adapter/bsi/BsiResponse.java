package com.artivisi.paymentgateway.adapter.bsi;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Outbound BSI message (gateway → bank). Field set + order + serialization mirror legacy
 * bsm-makara's {@code MakaraResponse} so the wire format is byte-compatible: {@code NON_EMPTY}
 * omits null/empty fields (echo fields, reversal-only fields, and amounts on OPEN) exactly as
 * bsm-makara does.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BsiResponse {
    private String responseCode;
    private String responseMessage;
    private String action;
    private String nomorPembayaran;
    private String nomorInvoice;
    private String jenisAkun;
    private String referensiPembayaran;
    private String referensiReversal;
    private BigDecimal tagihanTotal;
    private BigDecimal tagihanEfektif;
    private BigDecimal akumulasiPembayaran;
    private String nama;
    private String keterangan;
    private String kodeChannel;
    private String kodeBank;
    private String kodeTerminal;
    private String idTransaksi;
    private String tanggalTransaksi;
    private String tanggalTransaksiAsal;

    /** Error reply carries only the code + message (everything else omitted via NON_EMPTY), as bsm-makara does. */
    public static BsiResponse error(String responseCode, String responseMessage) {
        return BsiResponse.builder().responseCode(responseCode).responseMessage(responseMessage).build();
    }
}
