package com.artivisi.paymentgateway.web.viewmodel;

/** One row in the audit log table. */
public record AuditRowView(
        String day, String time, String iso, String actor, String actorClass,
        String event, String eventClass, String entityType, String entityIdShort, String entityIdFull,
        String detail, String detailFull,
        /**
         * The row's subject in identifiers a person recognises — bill number, VA number, payer, or a
         * bank reference. Null when the entity has none, in which case the screen falls back to the
         * short id. An audit log addressed only by UUID is unusable by finance, who know a payment by
         * the number the student typed at the ATM.
         */
        String subject) {
}
