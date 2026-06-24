package com.artivisi.paymentgateway.adapter.bsi;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.service.ChargeService;
import com.artivisi.paymentgateway.service.ConsumerService;
import com.artivisi.paymentgateway.service.EscrowAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.path.json.config.JsonPathConfig;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;

class BsiAdapterIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String SHARED_KEY = "bsi-shared-key";
    private static final String TGL = "2026-06-24T10:00:00";

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;

    private String escrowCode;
    private String vaNumber;

    private static EscrowAccountRequest escrowRequest(String code) {
        return new EscrowAccountRequest(code, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, "bsi-client", SHARED_KEY, null, null, null, null,
                null, null, "910900111", "Operator Settlement", "91099", "910", 10, null, null);
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        escrowCode = "bsi-adp-" + n;
        vaNumber = "9100000" + String.format("%03d", n);
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "bsiadp-consumer-" + n, "Academic", "bsiadp-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        escrowService.create(escrowRequest(escrowCode));
        chargeService.create(consumer, new CreateChargeRequest(
                "bsiadp-ref-" + n, "Student", null, null, ChargeType.CLOSED, new BigDecimal("1000000"), null,
                List.of(new ChargeAccountRequest(escrowCode, vaNumber))));
    }

    private static String checksum(String nomorPembayaran) {
        return BsiChecksum.compute(nomorPembayaran, SHARED_KEY, TGL);
    }

    private Map<String, Object> body(String action, String va, String checksum, BigDecimal nilai) {
        Map<String, Object> m = new HashMap<>();
        m.put("action", action);
        m.put("checksum", checksum);
        m.put("nomorPembayaran", va);
        m.put("idTransaksi", "TRX-" + va);
        m.put("tanggalTransaksi", TGL);
        if (nilai != null) {
            m.put("nilai", nilai);
        }
        return m;
    }

    private static final RestAssuredConfig BIG_DECIMAL = RestAssuredConfig.config()
            .jsonConfig(JsonConfig.jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));

    private io.restassured.response.Response post(Map<String, Object> body) {
        return given().config(BIG_DECIMAL).contentType("application/json").body(body).when().post("/api/bank/bsi");
    }

    @Test
    void inquiry_returnsBillDetails() {
        post(body("inquiry", vaNumber, checksum(vaNumber), null))
                .then().statusCode(200)
                .body("responseCode", equalTo("00"))
                .body("nama", equalTo("Student"))
                .body("jenisAkun", equalTo("CLOSED"))
                .body("tagihanTotal", comparesEqualTo(new BigDecimal("1000000")))
                .body("tagihanEfektif", comparesEqualTo(new BigDecimal("1000000")));
    }

    @Test
    void payment_settlesChargeAndReportsCumulative() {
        post(body("payment", vaNumber, checksum(vaNumber), new BigDecimal("1000000")))
                .then().statusCode(200)
                .body("responseCode", equalTo("00"))
                .body("akumulasiPembayaran", comparesEqualTo(new BigDecimal("1000000")))
                .body("tagihanEfektif", comparesEqualTo(new BigDecimal("0")));

        // Paid charge is no longer payable on inquiry.
        post(body("inquiry", vaNumber, checksum(vaNumber), null))
                .then().statusCode(200).body("responseCode", equalTo("03"));
    }

    @Test
    void payment_isIdempotentOnIdTransaksi() {
        Map<String, Object> payment = body("payment", vaNumber, checksum(vaNumber), new BigDecimal("1000000"));
        post(payment).then().body("responseCode", equalTo("00"));
        post(payment).then().body("responseCode", equalTo("00"))
                .body("akumulasiPembayaran", comparesEqualTo(new BigDecimal("1000000")));
    }

    @Test
    void invalidChecksum_isRejected() {
        post(body("inquiry", vaNumber, "deadbeef", null))
                .then().statusCode(200).body("responseCode", equalTo("25"));
    }

    @Test
    void unknownVa_isInvalidAccount() {
        String unknown = "9109999999";
        post(body("inquiry", unknown, checksum(unknown), null))
                .then().statusCode(200).body("responseCode", equalTo("03"));
    }

    @Test
    void closedWrongAmount_isInvalidAmount() {
        post(body("payment", vaNumber, checksum(vaNumber), new BigDecimal("999")))
                .then().statusCode(200).body("responseCode", equalTo("13"));
    }
}
