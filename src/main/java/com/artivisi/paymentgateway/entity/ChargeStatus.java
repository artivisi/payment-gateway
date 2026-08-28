package com.artivisi.paymentgateway.entity;

/**
 * PARTIALLY_PAID was removed with the INSTALLMENT charge type: a CLOSED charge is paid in one
 * transaction and an OPEN charge never completes, so neither has a partial state.
 */
public enum ChargeStatus {
    ACTIVE,
    PAID,
    EXPIRED,
    CANCELLED
}
