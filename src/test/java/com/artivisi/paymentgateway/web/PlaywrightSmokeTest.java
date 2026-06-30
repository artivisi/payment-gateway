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
import com.artivisi.paymentgateway.web.pages.LoginPage;
import com.artivisi.paymentgateway.web.pages.admin.AuditListPage;
import com.artivisi.paymentgateway.web.pages.admin.BankIpListPage;
import com.artivisi.paymentgateway.web.pages.admin.ChargeDetailPage;
import com.artivisi.paymentgateway.web.pages.admin.ChargeListPage;
import com.artivisi.paymentgateway.web.pages.admin.ConsumerEditPage;
import com.artivisi.paymentgateway.web.pages.admin.ConsumerFormPage;
import com.artivisi.paymentgateway.web.pages.admin.ConsumerListPage;
import com.artivisi.paymentgateway.web.pages.admin.DashboardPage;
import com.artivisi.paymentgateway.web.pages.admin.EscrowEditPage;
import com.artivisi.paymentgateway.web.pages.admin.EscrowListPage;
import com.artivisi.paymentgateway.web.pages.admin.OperatorEditPage;
import com.artivisi.paymentgateway.web.pages.admin.OperatorListPage;
import com.artivisi.paymentgateway.web.pages.admin.PaymentDetailPage;
import com.artivisi.paymentgateway.web.pages.admin.PaymentListPage;
import com.artivisi.paymentgateway.web.pages.admin.ReconciliationPage;
import com.artivisi.paymentgateway.web.pages.admin.RoleListPage;
import com.artivisi.paymentgateway.web.pages.admin.WebhookListPage;
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

    private DashboardPage login(Page page) {
        return LoginPage.open(page, base())
                .login(OPERATOR_USER, OPERATOR_PASS)
                .verify(currentTotp());
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
            LoginPage loginPage = new LoginPage(page, base());
            assertThat(page.url()).contains("/login");
            assertThat(loginPage.root().count()).isEqualTo(1);
            browser.close();
        }
    }

    @Test
    void dashboardRenders() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            DashboardPage dashboard = login(page);
            page.navigate(base() + "/");   // redirects to /admin
            assertThat(page.url()).endsWith("/admin");
            assertThat(page.title()).isEqualTo("Dashboard · Payment Gateway");
            assertThat(dashboard.root().textContent()).contains("Escrows").contains("Consumers");
            assertThat(dashboard.logo().count()).isEqualTo(1);
            browser.close();
        }
    }

    @Test
    void browseSectionsRender() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            assertThat(EscrowListPage.open(page, base()).root().count()).as("escrow-list").isEqualTo(1);
            assertThat(ChargeListPage.open(page, base()).root().count()).as("charge-list").isEqualTo(1);
            assertThat(PaymentListPage.open(page, base()).root().count()).as("payment-list").isEqualTo(1);
            assertThat(ReconciliationPage.open(page, base()).root().count()).as("reconciliation-list").isEqualTo(1);
            assertThat(WebhookListPage.open(page, base()).root().count()).as("webhook-list").isEqualTo(1);
            assertThat(OperatorListPage.open(page, base()).root().count()).as("operator-list").isEqualTo(1);
            assertThat(RoleListPage.open(page, base()).root().count()).as("role-list").isEqualTo(1);
            assertThat(BankIpListPage.open(page, base()).root().count()).as("bank-ip-rule-list").isEqualTo(1);
            assertThat(AuditListPage.open(page, base()).root().count()).as("audit-list").isEqualTo(1);
            browser.close();
        }
    }

    @Test
    void escrowEditPageRenders() {
        EscrowAccount escrow = escrowAccountService.create(escrowRequest("pw-escrow-" + SEQ.incrementAndGet()));
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            EscrowEditPage editPage = EscrowEditPage.open(page, base(), escrow.getId());
            assertThat(editPage.root().count()).isEqualTo(1);
            // unlocked (no VAs) → structural selects rendered (verifies the SpEL T()/list expressions)
            assertThat(editPage.providerSelect().count()).isEqualTo(1);
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
            ConsumerListPage list = ConsumerFormPage.open(page, base())
                    .fillCode(code)
                    .fillName("UI Test")
                    .fillClientId("ui-client-" + n)
                    .fillClientSecret("ui-secret")
                    .fillWebhookUrl("https://hook.example/ui")
                    .selectStatus("ACTIVE")
                    .submit();
            assertThat(list.root().textContent()).contains(code);
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
            ConsumerListPage list = ConsumerEditPage.open(page, base(), consumer.getId())
                    .fillName("Updated Name " + n)
                    .submit();
            assertThat(list.root().textContent()).contains("Updated Name " + n);
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
            OperatorListPage list = OperatorEditPage.open(page, base(), saved.getId())
                    .fillFullName("Updated Full Name " + n)
                    .submit();
            assertThat(list.root().textContent()).contains("Updated Full Name " + n);
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
            ChargeDetailPage detailPage = ChargeDetailPage.open(page, base(), chargeId);
            assertThat(detailPage.root().count()).isEqualTo(1);
            assertThat(detailPage.root().textContent())
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
            PaymentDetailPage detailPage = PaymentDetailPage.open(page, base(), paymentId);
            assertThat(detailPage.root().count()).isEqualTo(1);
            assertThat(detailPage.root().textContent())
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
            ReconciliationPage recon = ReconciliationPage.open(page, base())
                    .selectEscrow("pw-recon-" + n)
                    .fillPeriod("2026-06-25")
                    .uploadSettlement(new FilePayload("settlement.csv", "text/csv",
                            CsvFixtures.bytes("/testdata/reconciliation/settlement-sample.csv")))
                    .submit();
            assertThat(recon.root().textContent()).contains("Reconciliation completed:");
            browser.close();
        }
    }
}
