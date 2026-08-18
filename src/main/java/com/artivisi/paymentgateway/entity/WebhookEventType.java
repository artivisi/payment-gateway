package com.artivisi.paymentgateway.entity;

public enum WebhookEventType {
    PAYMENT_RECEIVED,
    CHARGE_PAID,
    PAYMENT_REVERSED,
    /**
     * The charge is no longer collectible: its VAs are retired and an inquiry now answers NOT_FOUND.
     * Emitted whoever asked — the consumer itself, an operator, or an ops script — because a
     * consumer's charge mirror has to be derived from these events rather than from the assumption
     * that it is the only actor. Carries no payment.
     */
    CHARGE_CANCELLED
}
