package com.artivisi.paymentgateway.entity;

public enum DiscrepancyType {
    /** Settlement credit matched a payment but amounts differ. */
    AMOUNT_MISMATCH,
    /** Bank settled a credit the gateway never recorded; recovered (payment created + webhook forwarded). */
    PAID_NOT_NOTIFIED_RECOVERED,
    /** Gateway recorded a payment the bank did not settle. */
    NOTIFIED_NOT_SETTLED,
    /** Two settlement credits share the same reference. */
    DUPLICATE,
    /** Settlement credit for a VA number unknown to this escrow. */
    UNMATCHED_CREDIT,
    /** Paid-not-notified credit that could not be recovered (e.g. charge cancelled / amount rule). */
    RECOVERY_FAILED
}
