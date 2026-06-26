package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.CsvFixtures;
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
import com.artivisi.paymentgateway.service.ChargeService;
import com.artivisi.paymentgateway.service.ConsumerService;
import com.artivisi.paymentgateway.service.EscrowAccountService;
import com.artivisi.paymentgateway.service.PaymentApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * Drives the full reconciliation scenario from CSV fixtures in {@code src/test/resources/testdata}:
 * seed escrow/consumer/charges/payments, then upload the settlement statement via the import
 * endpoint and assert every classified outcome (see {@code reconciliation/expected-discrepancies.csv}).
 */
class ReconciliationCsvImportTest extends AbstractIntegrationTest {

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired PaymentApplicationService paymentService;

    @Test
    void reconcileFromCsvFixtures() {
        Map<String, EscrowAccount> escrows = new HashMap<>();
        for (Map<String, String> row : CsvFixtures.rows("/testdata/escrow-accounts.csv")) {
            EscrowAccount escrow = escrowService.create(new EscrowAccountRequest(
                    row.get("code"), row.get("provider"),
                    HostingModel.valueOf(row.get("hostingModel")),
                    TransportProtocol.valueOf(row.get("transport")),
                    AuthScheme.valueOf(row.get("authScheme")),
                    EscrowEnvironment.valueOf(row.get("activeEnvironment")),
                    null, null, null, null, null, null, null, null,
                    row.get("settlementAccountNumber"), row.get("settlementAccountName"),
                    row.get("companyId"), row.get("vaPrefix"),
                    Integer.parseInt(row.get("vaDigitLength")), null, null));
            escrows.put(escrow.getCode(), escrow);
        }

        Map<String, Consumer> consumers = new HashMap<>();
        for (Map<String, String> row : CsvFixtures.rows("/testdata/consumers.csv")) {
            Consumer consumer = consumerService.create(new ConsumerRequest(
                    row.get("code"), row.get("name"), row.get("clientId"), row.get("clientSecret"),
                    row.get("webhookUrl"), ConsumerStatus.valueOf(row.get("status"))));
            consumers.put(row.get("code"), consumer);
        }

        for (Map<String, String> row : CsvFixtures.rows("/testdata/charges.csv")) {
            chargeService.create(consumers.get(row.get("consumerCode")), new CreateChargeRequest(
                    row.get("consumerReference"), row.get("payerName"), null, null,
                    ChargeType.valueOf(row.get("chargeType")), new BigDecimal(row.get("amount")), null,
                    List.of(new ChargeAccountRequest(row.get("escrowCode"), row.get("vaNumber")))));
        }

        for (Map<String, String> row : CsvFixtures.rows("/testdata/payments.csv")) {
            paymentService.apply(escrows.get(row.get("escrowCode")), row.get("vaNumber"),
                    new BigDecimal(row.get("amount")), row.get("bankReference"),
                    Instant.parse(row.get("transactionTime")));
        }

        given().multiPart("file", "settlement-sample.csv",
                        CsvFixtures.bytes("/testdata/reconciliation/settlement-sample.csv"), "text/csv")
                .formParam("period", "2026-06-25")
                .when().post("/api/escrow-accounts/{code}/reconciliations/import", "demo-bsi")
                .then().statusCode(201)
                .body("matchedCount", equalTo(1))
                .body("recoveredCount", equalTo(1))
                .body("discrepancyCount", equalTo(5))
                .body("discrepancies.type", hasItems(
                        "DUPLICATE", "PAID_NOT_NOTIFIED_RECOVERED", "AMOUNT_MISMATCH",
                        "UNMATCHED_CREDIT", "NOTIFIED_NOT_SETTLED"));
    }
}
