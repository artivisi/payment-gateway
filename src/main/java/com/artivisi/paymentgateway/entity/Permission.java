package com.artivisi.paymentgateway.entity;

/**
 * The fixed vocabulary of admin-UI permissions (features → permissions). Each value is enforced by a
 * route rule in {@code SecurityConfig} / a {@code sec:authorize} check, so the set is code-defined.
 * Roles (data) bundle these; that mapping is what admins customise at runtime.
 */
public enum Permission {
    ESCROW_VIEW,
    ESCROW_MANAGE,
    CONSUMER_VIEW,
    CONSUMER_MANAGE,
    CHARGE_VIEW,
    PAYMENT_VIEW,
    RECONCILIATION_VIEW,
    WEBHOOK_VIEW,
    WEBHOOK_MANAGE,
    AUDIT_VIEW,
    ANALYSIS_VIEW,
    OPERATOR_MANAGE,
    ROLE_MANAGE,
    BANK_IP_MANAGE
}
