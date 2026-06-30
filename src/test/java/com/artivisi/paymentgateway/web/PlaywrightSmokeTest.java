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
import com.artivisi.paymentgateway.entity.Operator;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import com.artivisi.paymentgateway.repository.RoleRepository;
import com.artivisi.paymentgateway.service.ChargeService;
import com.artivisi.paymentgateway.service.ConsumerService;
import com.artivisi.paymentgateway.service.EscrowAccountService;
import com.artivisi.paymentgateway.service.PaymentApplicationService;
import com.artivisi.paymentgateway.service.TotpService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightSmokeTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String OPERATOR_USER = "pw-admin";
    private static final String OPERATOR_PASS = "playwright-pass-123456";

    @Autowired OperatorRepository operatorRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TotpService totpService;
    @Autowired EscrowAccountService escrowAccountService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired PaymentApplicationService paymentApplicationService;

    private String operatorSecret;

    @BeforeEach
    void seedOperator() {
        Operator op = operatorRepository.findByUsername(OPERATOR_USER).orElseGet(() -> {
            Operator o = new Operator();
            o.setUsername(OPERATOR_USER);
            o.setPasswordHash(passwordEncoder.encode(OPERATOR_PASS));
            o.setFullName("Playwright Admin");
            o.setRole(roleRepository.findByName("ADMIN").orElseThrow());
            o.setEnabled(true);
            o.setMfaEnabled(true);
            o.setMfaSecret(totpService.generateSecret());
            o.setMustChangePassword(false);
            o.setFailedAttempts(0);
            return operatorRepository.save(o);
        });
        this.operatorSecret = op.getMfaSecret();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private void login(Page page) {
        page.navigate(base() + "/login");
        page.getByTestId("username").fill(OPERATOR_USER);
        page.getByTestId("password").fill(OPERATOR_PASS);
        page.getByTestId("login-submit").click();
        page.waitForURL("**/mfa");
        page.getByTestId("mfa-code").fill(currentTotp());
        page.getByTestId("mfa-submit").click();
        page.waitForURL("**/admin");
    }

    private String currentTotp() {
        try {
            long counter = Math.floorDiv(new SystemTimeProvider().getTime(), 30);
            return new DefaultCodeGenerator().generate(operatorSecret, counter);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException(e);
        }
    }

    private EscrowAccountRequest escrowRequest(String code) {
        return new EscrowAccountRequest(
                code, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "900900111", "Settle", "90099", "900", 10, null, null);
    }

    @Test
    void unauthenticatedAdminRedirectsToLogin() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            page.navigate(base() + "/admin");
            assertThat(page.url()).contains("/login");
            assertThat(page.getByTestId("login-page").count()).isEqualTo(1);
            browser.close();
        }
    }

    @Test
    void dashboardRenders() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            page.navigate(base() + "/");   // redirects to /admin
            assertThat(page.url()).endsWith("/admin");
            assertThat(page.title()).isEqualTo("Dashboard · Payment Gateway");
            assertThat(page.getByTestId("dashboard").textContent()).contains("Escrows").contains("Consumers");
            assertThat(page.getByTestId("logo").count()).isEqualTo(1);
            browser.close();
        }
    }

    @Test
    void browseSectionsRender() {
        Map<String, String> sections = Map.of(
                "/admin/escrow-accounts", "escrow-list",
                "/admin/charges", "charge-list",
                "/admin/payments", "payment-list",
                "/admin/reconciliations", "reconciliation-list",
                "/admin/webhooks", "webhook-list",
                "/admin/operators", "operator-list",
                "/admin/roles", "role-list",
                "/admin/bank-ip-rules", "bank-ip-rule-list",
                "/admin/audit", "audit-list");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            sections.forEach((path, testId) -> {
                page.navigate(base() + path);
                assertThat(page.getByTestId(testId).count()).as(path).isEqualTo(1);
            });
            browser.close();
        }
    }

    @Test
    void escrowEditPageRenders() {
        var escrow = escrowAccountService.create(new EscrowAccountRequest(
                "pw-escrow-" + SEQ.incrementAndGet(), "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "900900111", "Settle", "90099", "900", 10, null, null));
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            page.navigate(base() + "/admin/escrow-accounts/" + escrow.getId() + "/edit");
            assertThat(page.getByTestId("escrow-edit").count()).isEqualTo(1);
            // unlocked (no VAs) → structural selects rendered (verifies the SpEL T()/list expressions)
            assertThat(page.getByTestId("provider").count()).isEqualTo(1);
            browser.close();
        }
    }

    @Test
    void createConsumerThroughUi() {
        int n = SEQ.incrementAndGet();
        String code = "ui-consumer-" + n;
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            page.navigate(base() + "/admin/consumers/new");
            page.getByTestId("code").fill(code);
            page.getByTestId("name").fill("UI Test");
            page.getByTestId("client-id").fill("ui-client-" + n);
            page.getByTestId("client-secret").fill("ui-secret");
            page.getByTestId("webhook-url").fill("https://hook.example/ui");
            page.getByTestId("status").selectOption("ACTIVE");
            page.getByTestId("form-submit").click();
            assertThat(page.getByTestId("consumer-list").textContent()).contains(code);
            browser.close();
        }
    }

    @Test
    void editConsumerThroughUi() {
        int n = SEQ.incrementAndGet();
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "pw-edit-consumer-" + n, "Original Name", "pw-edit-client-" + n,
                "pw-secret", "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            page.navigate(base() + "/admin/consumers/" + consumer.getId() + "/edit");
            assertThat(page.getByTestId("consumer-edit").count()).isEqualTo(1);
            page.getByTestId("name").fill("Updated Name " + n);
            page.getByTestId("form-submit").click();
            page.waitForURL("**/consumers");
            assertThat(page.getByTestId("consumer-list").textContent()).contains("Updated Name " + n);
            browser.close();
        }
    }

    @Test
    void editOperatorThroughUi() {
        int n = SEQ.incrementAndGet();
        Operator op = new Operator();
        op.setUsername("pw-edit-op-" + n);
        op.setPasswordHash(passwordEncoder.encode("temp-pass-0001"));
        op.setFullName("Original Full Name " + n);
        op.setRole(roleRepository.findByName("OPERATOR").orElseThrow());
        op.setEnabled(true);
        op.setMfaEnabled(false);
        op.setMustChangePassword(false);
        op.setFailedAttempts(0);
        Operator saved = operatorRepository.save(op);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            page.navigate(base() + "/admin/operators/" + saved.getId() + "/edit");
            assertThat(page.getByTestId("operator-edit").count()).isEqualTo(1);
            page.getByTestId("full-name").fill("Updated Full Name " + n);
            page.getByTestId("form-submit").click();
            page.waitForURL("**/operators");
            assertThat(page.getByTestId("operator-list").textContent()).contains("Updated Full Name " + n);
            browser.close();
        }
    }

    @Test
    void chargeDetailPageRenders() {
        int n = SEQ.incrementAndGet();
        String vaNumber = "900" + String.format("%07d", n);
        EscrowAccount escrow = escrowAccountService.create(escrowRequest("pw-chr-escrow-" + n));
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "pw-chr-consumer-" + n, "Detail Consumer", "pw-chr-client-" + n,
                "pw-secret", "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        String chargeId = chargeService.create(consumer, new CreateChargeRequest(
                "CHR-REF-" + n, "Test Payer", null, null,
                ChargeType.CLOSED, new BigDecimal("500000"), null,
                List.of(new ChargeAccountRequest(escrow.getCode(), vaNumber)))).response().id();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            page.navigate(base() + "/admin/charges/" + chargeId);
            assertThat(page.getByTestId("charge-detail").count()).isEqualTo(1);
            assertThat(page.getByTestId("charge-detail").textContent())
                    .contains("CHR-REF-" + n)
                    .contains("CLOSED")
                    .contains(vaNumber);
            browser.close();
        }
    }

    @Test
    void paymentDetailPageRenders() {
        int n = SEQ.incrementAndGet();
        String vaNumber = "900" + String.format("%07d", n);
        EscrowAccount escrow = escrowAccountService.create(escrowRequest("pw-pay-escrow-" + n));
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "pw-pay-consumer-" + n, "Pay Consumer", "pw-pay-client-" + n,
                "pw-secret", "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        chargeService.create(consumer, new CreateChargeRequest(
                "PAY-REF-" + n, "Payer", null, null,
                ChargeType.OPEN, new BigDecimal("500000"), null,
                List.of(new ChargeAccountRequest(escrow.getCode(), vaNumber))));
        String bankRef = "BANK-" + n;
        String paymentId = paymentApplicationService.apply(
                escrow, vaNumber, new BigDecimal("100000"), bankRef, Instant.now()).getId();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            page.navigate(base() + "/admin/payments/" + paymentId);
            assertThat(page.getByTestId("payment-detail").count()).isEqualTo(1);
            assertThat(page.getByTestId("payment-detail").textContent())
                    .contains(bankRef)
                    .contains(vaNumber)
                    .contains("PAY-REF-" + n);
            browser.close();
        }
    }

    @Test
    void reconciliationAdminImportShowsFlash() {
        int n = SEQ.incrementAndGet();
        // Escrow matching the settlement-sample.csv VA number space (prefix=940, length=10)
        escrowAccountService.create(new EscrowAccountRequest(
                "pw-recon-" + n, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "940090111", "Settle", "94099", "940", 10, null, null));

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            page.navigate(base() + "/admin/reconciliations");
            page.getByTestId("escrow-code").selectOption("pw-recon-" + n);
            page.getByTestId("period").fill("2026-06-25");
            page.getByTestId("settlement-file").setInputFiles(new FilePayload(
                    "settlement.csv", "text/csv",
                    CsvFixtures.bytes("/testdata/reconciliation/settlement-sample.csv")));
            page.getByTestId("form-submit").click();
            page.waitForURL("**/reconciliations");
            assertThat(page.getByTestId("reconciliation-list").textContent())
                    .contains("Reconciliation completed:");
            browser.close();
        }
    }
}
