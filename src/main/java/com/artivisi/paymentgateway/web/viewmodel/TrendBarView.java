package com.artivisi.paymentgateway.web.viewmodel;

/** One bar in the dashboard's 14-day collections trend. */
public record TrendBarView(String label, int heightPx, boolean highlight, String tooltip) {
}
