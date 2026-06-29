package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.VirtualAccount;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled sweep that marks charges past their {@code expiresAt} as EXPIRED and cancels
 * their remaining active VAs. Runs on a config-driven interval (gateway.reaper.interval-ms).
 */
@Service
public class ExpiryReaper {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReaper.class);

    private static final List<ChargeStatus> EXPIRABLE = List.of(
            ChargeStatus.ACTIVE, ChargeStatus.PARTIALLY_PAID);

    private final ChargeRepository chargeRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final AuditService auditService;

    public ExpiryReaper(ChargeRepository chargeRepository,
                        VirtualAccountRepository virtualAccountRepository,
                        AuditService auditService) {
        this.chargeRepository = chargeRepository;
        this.virtualAccountRepository = virtualAccountRepository;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${gateway.reaper.interval-ms}")
    public void sweep() {
        List<Charge> expired = chargeRepository.findExpired(Instant.now(), EXPIRABLE);
        if (expired.isEmpty()) {
            return;
        }
        log.info("ExpiryReaper: expiring {} charge(s)", expired.size());
        for (Charge charge : expired) {
            expireOne(charge.getId());
        }
    }

    /** Expire a single charge in its own transaction so one failure doesn't abort the sweep. */
    @Transactional
    public void expireOne(String chargeId) {
        chargeRepository.findById(chargeId).ifPresent(charge -> {
            if (!EXPIRABLE.contains(charge.getStatus())) {
                return;
            }
            List<VirtualAccount> vas = virtualAccountRepository.findByChargeId(chargeId);
            for (VirtualAccount va : vas) {
                if (va.getStatus() == VirtualAccountStatus.ACTIVE) {
                    va.setStatus(VirtualAccountStatus.EXPIRED);
                    virtualAccountRepository.save(va);
                }
            }
            charge.setStatus(ChargeStatus.EXPIRED);
            chargeRepository.save(charge);
            auditService.recordAs("system", "CHARGE_EXPIRED", "Charge", chargeId,
                    "consumerReference=" + charge.getConsumerReference()
                            + " expiresAt=" + charge.getExpiresAt());
        });
    }
}
