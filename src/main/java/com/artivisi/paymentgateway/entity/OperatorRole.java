package com.artivisi.paymentgateway.entity;

/**
 * Admin-UI roles (PCI Req 7, least privilege).
 * ADMIN — manage escrows (bank credentials), operators, IP rules; all OPERATOR rights.
 * OPERATOR — daily ops: charges, payments, webhooks, reconciliation.
 * AUDITOR — read-only access to all views.
 */
public enum OperatorRole {
    ADMIN,
    OPERATOR,
    AUDITOR
}
