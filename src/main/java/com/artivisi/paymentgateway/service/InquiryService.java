package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.dto.InquiryResult;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.VirtualAccount;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Resolves a VA inquiry for an escrow (the bank-&gt;gateway path). Anything not currently
 * payable — unknown, cancelled, paid, or expired — surfaces as {@link NotFoundException},
 * which adapters map to the bank's NOT_FOUND.
 */
@Service
public class InquiryService {

    private final VirtualAccountRepository virtualAccountRepository;

    public InquiryService(VirtualAccountRepository virtualAccountRepository) {
        this.virtualAccountRepository = virtualAccountRepository;
    }

    @Transactional(readOnly = true)
    public InquiryResult inquire(EscrowAccount escrow, String vaNumber) {
        // Numbers are reusable: prefer the ACTIVE generation; a retired-only number is not payable.
        List<VirtualAccount> generations = virtualAccountRepository
                .findByEscrowAccountIdAndVaNumberOrderByCreatedAtDesc(escrow.getId(), vaNumber);
        if (generations.isEmpty()) {
            throw new NotFoundException("VA not found: " + vaNumber);
        }
        VirtualAccount va = generations.stream()
                .filter(g -> g.getStatus() == VirtualAccountStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("VA not active: " + vaNumber));
        Charge charge = va.getCharge();
        if (charge.getStatus() == ChargeStatus.CANCELLED
                || charge.getStatus() == ChargeStatus.PAID
                || charge.getStatus() == ChargeStatus.EXPIRED) {
            throw new NotFoundException("charge not payable: " + vaNumber);
        }
        if (charge.getExpiresAt() != null && Instant.now().isAfter(charge.getExpiresAt())) {
            throw new NotFoundException("charge expired: " + vaNumber);
        }
        BigDecimal remaining = charge.getAmount().subtract(charge.getCumulativePaid());
        return new InquiryResult(vaNumber, charge.getConsumerReference(), charge.getPayerName(),
                charge.getDescription(), charge.getChargeType(), charge.getAmount(), remaining);
    }
}
