package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ChargeResponse;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.VirtualAccount;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;
import com.artivisi.paymentgateway.exception.DuplicateException;
import com.artivisi.paymentgateway.exception.InvalidRequestException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ChargeService {

    private final ChargeRepository chargeRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final EscrowAccountRepository escrowAccountRepository;
    private final NumberSpaceValidator numberSpaceValidator;
    private final AuditService auditService;

    public ChargeService(ChargeRepository chargeRepository,
                         VirtualAccountRepository virtualAccountRepository,
                         EscrowAccountRepository escrowAccountRepository,
                         NumberSpaceValidator numberSpaceValidator,
                         AuditService auditService) {
        this.chargeRepository = chargeRepository;
        this.virtualAccountRepository = virtualAccountRepository;
        this.escrowAccountRepository = escrowAccountRepository;
        this.numberSpaceValidator = numberSpaceValidator;
        this.auditService = auditService;
    }

    /** Result of create: the charge view plus whether it was newly created (vs idempotent hit). */
    public record CreateChargeOutcome(ChargeResponse response, boolean created) {
    }

    @Transactional
    public CreateChargeOutcome create(Consumer consumer, CreateChargeRequest request) {
        Optional<Charge> existing = chargeRepository
                .findByConsumerIdAndConsumerReference(consumer.getId(), request.consumerReference());
        if (existing.isPresent()) {
            Charge charge = existing.get();
            List<VirtualAccount> vas = virtualAccountRepository.findByChargeId(charge.getId());
            return new CreateChargeOutcome(ChargeResponse.from(charge, vas), false);
        }

        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new InvalidRequestException("amount must be positive");
        }

        Charge charge = new Charge();
        charge.setConsumer(consumer);
        charge.setConsumerReference(request.consumerReference());
        charge.setPayerName(request.payerName());
        charge.setPayerEmail(request.payerEmail());
        charge.setPayerPhone(request.payerPhone());
        charge.setChargeType(request.chargeType());
        charge.setAmount(request.amount());
        charge.setCumulativePaid(BigDecimal.ZERO);
        charge.setStatus(ChargeStatus.ACTIVE);
        charge.setExpiresAt(request.expiresAt());
        Charge savedCharge = chargeRepository.save(charge);

        List<VirtualAccount> vas = new ArrayList<>();
        Set<String> targetedEscrows = new HashSet<>();
        for (ChargeAccountRequest account : request.accounts()) {
            if (!targetedEscrows.add(account.escrowCode())) {
                throw new InvalidRequestException("duplicate escrow in charge: " + account.escrowCode());
            }
            EscrowAccount escrow = escrowAccountRepository.findByCode(account.escrowCode())
                    .orElseThrow(() -> new NotFoundException("escrow not found: " + account.escrowCode()));
            if (!escrow.isEnabled()) {
                throw new InvalidRequestException("escrow is disabled: " + account.escrowCode());
            }
            numberSpaceValidator.validate(escrow, account.vaNumber());
            if (virtualAccountRepository
                    .findByEscrowAccountIdAndVaNumberAndStatus(escrow.getId(), account.vaNumber(),
                            VirtualAccountStatus.ACTIVE).isPresent()) {
                throw new DuplicateException(
                        "vaNumber already active for escrow " + escrow.getCode() + ": " + account.vaNumber());
            }
            VirtualAccount va = new VirtualAccount();
            va.setCharge(savedCharge);
            va.setEscrowAccount(escrow);
            va.setVaNumber(account.vaNumber());
            va.setStatus(VirtualAccountStatus.ACTIVE);
            vas.add(virtualAccountRepository.save(va));
        }

        auditService.record("CHARGE_CREATED", "Charge", savedCharge.getId(),
                "consumerReference=" + savedCharge.getConsumerReference()
                        + " type=" + savedCharge.getChargeType() + " amount=" + savedCharge.getAmount());
        return new CreateChargeOutcome(ChargeResponse.from(savedCharge, vas), true);
    }

    @Transactional(readOnly = true)
    public ChargeResponse get(Consumer consumer, String id) {
        Charge charge = chargeRepository.findByIdAndConsumerId(id, consumer.getId())
                .orElseThrow(() -> new NotFoundException("charge not found: " + id));
        return ChargeResponse.from(charge, virtualAccountRepository.findByChargeId(charge.getId()));
    }

    @Transactional
    public ChargeResponse cancel(Consumer consumer, String id) {
        Charge charge = chargeRepository.findByIdAndConsumerId(id, consumer.getId())
                .orElseThrow(() -> new NotFoundException("charge not found: " + id));
        if (charge.getStatus() == ChargeStatus.PAID) {
            throw new InvalidRequestException("cannot cancel a paid charge");
        }
        charge.setStatus(ChargeStatus.CANCELLED);
        List<VirtualAccount> vas = virtualAccountRepository.findByChargeId(charge.getId());
        for (VirtualAccount va : vas) {
            if (va.getStatus() == VirtualAccountStatus.ACTIVE) {
                va.setStatus(VirtualAccountStatus.CANCELLED);
            }
            virtualAccountRepository.save(va);
        }
        auditService.record("CHARGE_CANCELLED", "Charge", charge.getId(),
                "consumerReference=" + charge.getConsumerReference());
        return ChargeResponse.from(charge, vas);
    }
}
