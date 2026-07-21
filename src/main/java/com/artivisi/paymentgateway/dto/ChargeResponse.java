package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.VirtualAccount;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ChargeResponse(
        String id,
        String consumerReference,
        String payerName,
        String payerEmail,
        String payerPhone,
        String description,
        ChargeType chargeType,
        BigDecimal amount,
        BigDecimal cumulativePaid,
        ChargeStatus status,
        Instant expiresAt,
        List<Account> accounts,
        Instant createdAt
) {
    public record Account(String escrowCode, String vaNumber, VirtualAccountStatus status) {
    }

    public static ChargeResponse from(Charge c, List<VirtualAccount> vas) {
        List<Account> accounts = vas.stream()
                .map(va -> new Account(va.getEscrowAccount().getCode(), va.getVaNumber(), va.getStatus()))
                .toList();
        return new ChargeResponse(
                c.getId(), c.getConsumerReference(), c.getPayerName(), c.getPayerEmail(), c.getPayerPhone(),
                c.getDescription(), c.getChargeType(), c.getAmount(), c.getCumulativePaid(), c.getStatus(),
                c.getExpiresAt(), accounts, c.getCreatedAt());
    }
}
