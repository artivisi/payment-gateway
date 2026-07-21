package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.ChargeType;

import java.math.BigDecimal;

/**
 * What an adapter returns to the bank on inquiry. {@code remainingAmount} is the live
 * effective amount ({@code totalAmount - cumulativePaid}) shared across sibling VAs.
 */
public record InquiryResult(
        String vaNumber,
        String consumerReference,
        String payerName,
        String description,
        ChargeType chargeType,
        BigDecimal totalAmount,
        BigDecimal remainingAmount
) {
}
