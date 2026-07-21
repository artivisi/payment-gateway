package com.artivisi.paymentgateway.web.viewmodel;

/** One row in the dashboard's "Latest payments" panel. */
public record RecentPaymentView(String when, String va, String amount, String chargeShort, String chargeHref) {
}
