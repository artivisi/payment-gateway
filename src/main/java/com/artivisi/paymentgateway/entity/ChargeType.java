package com.artivisi.paymentgateway.entity;

/**
 * How a charge behaves when paid. Two types, matching the two VA types banks generally offer.
 *
 * <p>There is deliberately no INSTALLMENT type. Instalments are the consumer's concern (the
 * receivables application holds the schedule and reprices one CLOSED charge over time), and a
 * bank-side instalment VA is bank-specific, which would tie a charge to one bank. The type existed
 * until 2026-08 and never carried a production charge.
 */
public enum ChargeType {
    OPEN,
    CLOSED
}
