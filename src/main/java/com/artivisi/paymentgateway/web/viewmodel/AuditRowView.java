package com.artivisi.paymentgateway.web.viewmodel;

/** One row in the audit log table. */
public record AuditRowView(
        String day, String time, String iso, String actor, String actorClass,
        String event, String eventClass, String entityType, String entityIdShort, String entityIdFull,
        String detail, String detailFull) {
}
