package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.dto.EscrowUpdateRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.exception.InvalidRequestException;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Escrow edit: operational updates, secret rotation (blank keeps), structural freeze, disable. */
class EscrowEditIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired EscrowAccountRepository escrowRepository;

    private EscrowAccount seedEscrow(String secret) {
        int n = SEQ.incrementAndGet();
        return escrowService.create(new EscrowAccountRequest("ee-bsi-" + n, "bsi", HostingModel.SELF_HOSTED,
                TransportProtocol.REST_JSON, AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX,
                "cid", secret, null, null, null, null, null, null,
                "900900111", "Operator Settlement", "90099", "900", 10, null, null));
    }

    private EscrowUpdateRequest update(EscrowAccount e, String settlementName, String clientSecret) {
        return new EscrowUpdateRequest(e.getProvider(), e.getHostingModel(), e.getTransport(), e.getAuthScheme(),
                e.getActiveEnvironment(), e.getClientId(), clientSecret, e.getPartnerId(), e.getChannelId(),
                null, e.getPublicKey(), e.getSandboxBaseUrl(), e.getProductionBaseUrl(),
                e.getSettlementAccountNumber(), settlementName, e.getCompanyId(), e.getVaPrefix(),
                e.getVaDigitLength(), e.getMerchantTag(), e.getInstitutionTag());
    }

    @Test
    void operationalFieldUpdates_persist() {
        EscrowAccount e = seedEscrow("orig-secret");
        escrowService.update(e.getId(), update(e, "Renamed Settlement", null));
        assertThat(escrowRepository.findById(e.getId()).orElseThrow().getSettlementAccountName())
                .isEqualTo("Renamed Settlement");
    }

    @Test
    void secret_blankKeeps_nonBlankRotates() {
        EscrowAccount e = seedEscrow("orig-secret");

        escrowService.update(e.getId(), update(e, e.getSettlementAccountName(), null));
        assertThat(escrowRepository.findById(e.getId()).orElseThrow().getClientSecret()).isEqualTo("orig-secret");

        escrowService.update(e.getId(), update(e, e.getSettlementAccountName(), "rotated-secret"));
        assertThat(escrowRepository.findById(e.getId()).orElseThrow().getClientSecret()).isEqualTo("rotated-secret");
    }

    @Test
    void structuralFields_frozenWhenVasExist() {
        int n = SEQ.incrementAndGet();
        EscrowAccount e = seedEscrow("s");
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "ee-con-" + n, "C", "ee-cid-" + n, "sec", "https://h/x", ConsumerStatus.ACTIVE));
        chargeService.create(consumer, new CreateChargeRequest("ee-ref-" + n, "Payer", null, null,
                ChargeType.CLOSED, new BigDecimal("1000000"), null,
                List.of(new ChargeAccountRequest(e.getCode(), "9000000001"))));

        EscrowUpdateRequest changedProvider = new EscrowUpdateRequest("cimb", e.getHostingModel(), e.getTransport(),
                e.getAuthScheme(), e.getActiveEnvironment(), e.getClientId(), null, null, null, null, null, null, null,
                e.getSettlementAccountNumber(), e.getSettlementAccountName(), e.getCompanyId(), e.getVaPrefix(),
                e.getVaDigitLength(), null, null);

        assertThatThrownBy(() -> escrowService.update(e.getId(), changedProvider))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledEscrow_rejectsNewCharges() {
        int n = SEQ.incrementAndGet();
        EscrowAccount e = seedEscrow("s");
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "ee-con-" + n, "C", "ee-cid-" + n, "sec", "https://h/x", ConsumerStatus.ACTIVE));
        escrowService.setEnabled(e.getId(), false);

        assertThatThrownBy(() -> chargeService.create(consumer, new CreateChargeRequest("ee-ref-" + n, "Payer",
                null, null, ChargeType.CLOSED, new BigDecimal("1000000"), null,
                List.of(new ChargeAccountRequest(e.getCode(), "9000000002")))))
                .isInstanceOf(InvalidRequestException.class);
    }
}
