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
 * Scheduled sweep that retires the VAs of charges past their {@code expiresAt}.
 *
 * <p><b>Expiry is soft: the charge keeps its status.</b> Payability is already decided at read time —
 * {@code InquiryService} refuses a charge past {@code expiresAt}, and the payment path refuses it
 * too — so flipping the charge to EXPIRED adds no enforcement. What it does add is data loss: an
 * EXPIRED charge can never afterwards be observed as "eventually paid", which silently biases every
 * collection-aging study against exactly the aged bills such a study exists to measure. It also
 * diverges from the bank adapters this replaces, which derive expiry at read time and leave the
 * bill's own state alone.
 *
 * <p>Extending a deadline is therefore just moving {@code expiresAt} — no status to unwind, and the
 * charge remains payable the moment the new date is in the future.
 *
 * <p>The VA is still retired, because a VA number is a scarce resource that must be free for reuse
 * by the next bill; that is a separate concern from whether the debt is still owed.
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
        log.info("ExpiryReaper: retiring VAs for {} expired charge(s)", expired.size());
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
            // Deliberately NOT charge.setStatus(EXPIRED) — see the class javadoc. The debt is still
            // owed and still reportable; it simply cannot be collected on this VA any more.
            auditService.recordAs("system", "CHARGE_VA_RETIRED_ON_EXPIRY", "Charge", chargeId,
                    "consumerReference=" + charge.getConsumerReference()
                            + " expiresAt=" + charge.getExpiresAt()
                            + " chargeStatus=" + charge.getStatus() + " (unchanged)");
        });
    }
}
