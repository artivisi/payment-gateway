package com.artivisi.paymentgateway.web.viewmodel;

/** One row in the charges list table. "bankVa" is pre-joined ("BSI 9000000001, MAYBANK 8000000002" or "—"). */
public record ChargeRowView(
        String shortId, String fullId, String consumerCode, String payer, String type,
        String bankVa, String amount, String paid, String paidClass,
        String status, String statusClass, String created, String createdFull) {
}
