package com.artivisi.paymentgateway.web.viewmodel;

/** One row in the dashboard's "Needs attention" panel. {@code dotClass} is a status-dot color class. */
public record AttentionItemView(String dotClass, String text, String href) {
}
