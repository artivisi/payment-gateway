package com.artivisi.paymentgateway.adapter.demo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * The wire shapes of an invented bank. Every field here was chosen to teach, not copied from any
 * bank's specification: a real one belongs to the bank that wrote it and lives in the private
 * assembly with the adapter that speaks it.
 *
 * <p>It is still deliberately awkward in the ways real ones are — a single endpoint switching on an
 * {@code action}, amounts as strings, a shared secret hashed into a signature — because an adapter
 * written against a tidy imaginary protocol teaches nothing about writing the next real one.
 */
public final class DemoBankMessages {

    private DemoBankMessages() {
    }

    /** Inquiry, payment and reversal all arrive on one endpoint, told apart by {@code action}. */
    public record Request(
            String action,
            String accountNumber,
            String amount,
            String transactionId,
            String transactionTime,
            String signature
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            String responseCode,
            String responseMessage,
            String accountNumber,
            String billNumber,
            String payerName,
            String description,
            BigDecimal totalAmount,
            BigDecimal remainingAmount,
            String paymentReference
    ) {
        public static Response error(String code, String message) {
            return new Response(code, message, null, null, null, null, null, null, null);
        }
    }

    /**
     * Response codes. A closed set, and an adapter never invents one: the bank's switch matches on
     * these exactly, and an unknown code is read by the terminal as a failure of an unknown kind.
     */
    public static final class Code {
        public static final String OK = "00";
        public static final String UNKNOWN_ACCOUNT = "14";
        public static final String INVALID_SIGNATURE = "63";
        public static final String INVALID_AMOUNT = "13";
        public static final String UNKNOWN_ACTION = "12";

        private Code() {
        }
    }
}
