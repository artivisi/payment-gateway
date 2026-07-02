package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Routing of an inbound bank message to its escrow by provider + number space. A wrong resolution
 * is a cross-escrow payment, so every branch fails loud: no match, ambiguous overlap, blank input.
 * Each test uses its own provider name so escrows from other tests can't leak into the candidates.
 */
class EscrowResolverIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired EscrowResolver escrowResolver;

    private Consumer consumer;
    private String provider;

    private EscrowAccountRequest escrowRequest(String code, String prefix) {
        return new EscrowAccountRequest(code, provider, HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "960900111", "Operator Settlement", "96099", prefix, 10, null, null);
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        provider = "rslv-" + n;
        consumer = consumerService.create(new ConsumerRequest(
                "rslv-consumer-" + n, "Academic", "rslv-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
    }

    @Test
    void singleEscrowMatchingSpace_resolvesByPrefix() {
        EscrowAccount a = escrowService.create(escrowRequest("rslv-a-" + SEQ.get(), "9601"));
        escrowService.create(escrowRequest("rslv-b-" + SEQ.get(), "9602"));

        assertThat(escrowResolver.resolveForVaNumber(provider, "9601000001").getId())
                .isEqualTo(a.getId());
    }

    @Test
    void noEscrowMatchingSpace_failsLoud() {
        escrowService.create(escrowRequest("rslv-a-" + SEQ.get(), "9601"));

        assertThatThrownBy(() -> escrowResolver.resolveForVaNumber(provider, "9999000001"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("no " + provider + " escrow");
    }

    @Test
    void overlappingSpaces_disambiguatedByRegisteredVa() {
        escrowService.create(escrowRequest("rslv-a-" + SEQ.get(), "9603"));
        EscrowAccount b = escrowService.create(escrowRequest("rslv-b-" + SEQ.get(), "9603"));

        // The VA is registered in escrow B only.
        chargeService.create(consumer, new CreateChargeRequest(
                "rslv-ref-" + SEQ.get(), "Student", null, null, ChargeType.CLOSED, new BigDecimal("100000"), null,
                List.of(new ChargeAccountRequest(b.getCode(), "9603000001"))));

        assertThat(escrowResolver.resolveForVaNumber(provider, "9603000001").getId())
                .isEqualTo(b.getId());
    }

    @Test
    void overlappingSpaces_unregisteredVaIsAmbiguousAndFailsLoud() {
        escrowService.create(escrowRequest("rslv-a-" + SEQ.get(), "9603"));
        escrowService.create(escrowRequest("rslv-b-" + SEQ.get(), "9603"));

        assertThatThrownBy(() -> escrowResolver.resolveForVaNumber(provider, "9603999999"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void blankVaNumber_failsLoud() {
        assertThatThrownBy(() -> escrowResolver.resolveForVaNumber(provider, " "))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("required");
    }
}
