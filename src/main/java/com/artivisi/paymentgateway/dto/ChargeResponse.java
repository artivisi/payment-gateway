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
        String billNumber,
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
    /**
     * {@code interbankVaNumber} is the form a payer uses from a different bank, and it is returned
     * rather than left to the consumer to build. Composing it needs the escrow's interbank prefix,
     * which is a bank fact; a consumer that concatenates its own copy is right until the prefix
     * changes or a second escrow exists.
     */
    public record Account(String escrowCode, String vaNumber, String interbankVaNumber,
                          VirtualAccountStatus status) {
    }

    public static ChargeResponse from(Charge c, List<VirtualAccount> vas) {
        List<Account> accounts = vas.stream()
                .map(va -> new Account(va.getEscrowAccount().getCode(), va.getVaNumber(),
                        va.getInterbankVaNumber(), va.getStatus()))
                .toList();
        return new ChargeResponse(
                c.getId(), c.getConsumerReference(), c.getBillNumber(), c.getPayerName(), c.getPayerEmail(),
                c.getPayerPhone(), c.getDescription(), c.getChargeType(), c.getAmount(), c.getCumulativePaid(),
                c.getStatus(), c.getExpiresAt(), accounts, c.getCreatedAt());
    }
}
