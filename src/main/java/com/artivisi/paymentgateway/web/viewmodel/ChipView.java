package com.artivisi.paymentgateway.web.viewmodel;

/** One filter pill in a chip row (charges/payments/audit list headers). */
public record ChipView(String label, long count, boolean active, String href) {
}
