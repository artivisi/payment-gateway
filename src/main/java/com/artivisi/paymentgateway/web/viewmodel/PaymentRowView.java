package com.artivisi.paymentgateway.web.viewmodel;

/** One row in the payments list table. */
public record PaymentRowView(
        String id, String day, String time, String iso, String payer, String bank, String va, String bankReference,
        String amount, String statusLabel, String statusClass, String chargeShort, String chargeHref) {
}
