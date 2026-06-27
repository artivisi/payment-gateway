package com.artivisi.paymentgateway.entity;

public enum WebhookStatus {
    /** Awaiting delivery (or scheduled for retry after backoff). */
    PENDING,
    /** Claimed by the dispatcher and in flight. Reclaimed if it goes stale (crash mid-send). */
    SENDING,
    DELIVERED,
    FAILED
}
