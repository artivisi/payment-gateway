package com.artivisi.paymentgateway.web.viewmodel;

/** One dashboard KPI tile. {@code dotClass} is a Tailwind background-color utility class. */
public record KpiView(String label, String value, String sub, String dotClass) {
}
