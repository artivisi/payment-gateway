package com.artivisi.paymentgateway.service;

/** Outcome of one HTTP send attempt. {@code responseCode} is null when the call never completed. */
public record WebhookSendResult(boolean delivered, Integer responseCode, String error) {
}
