package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.entity.Operator;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import com.artivisi.paymentgateway.repository.RoleRepository;
import com.artivisi.paymentgateway.service.TotpService;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;
import io.restassured.filter.session.SessionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;

/**
 * Enforces the SecurityConfig URL-to-permission map at the HTTP layer with a fully stepped-up
 * session (form login + TOTP). RolePermissionIntegrationTest proves which authorities a role
 * grants; this proves the request matchers actually consult them — a mis-mapped rule fails here.
 */
class AdminRbacHttpIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASS = "rbac-http-pass-123456";
    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    @Autowired OperatorRepository operatorRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TotpService totpService;

    private String auditorUser;
    private String auditorSecret;
    private String adminUser;
    private String adminSecret;

    @BeforeEach
    void seedOperators() {
        int n = SEQ.incrementAndGet();
        auditorUser = "rbac-http-auditor-" + n;
        auditorSecret = seedOperator(auditorUser, "AUDITOR");
        adminUser = "rbac-http-admin-" + n;
        adminSecret = seedOperator(adminUser, "ADMIN");
    }

    private String seedOperator(String username, String roleName) {
        Operator o = new Operator();
        o.setUsername(username);
        o.setPasswordHash(passwordEncoder.encode(PASS));
        o.setFullName(username);
        o.setRole(roleRepository.findByName(roleName).orElseThrow());
        o.setEnabled(true);
        o.setMfaEnabled(true);
        o.setMfaSecret(totpService.generateSecret());
        o.setMustChangePassword(false);
        o.setFailedAttempts(0);
        return operatorRepository.save(o).getMfaSecret();
    }

    private static String totp(String secret) {
        try {
            long counter = Math.floorDiv(new SystemTimeProvider().getTime(), 30);
            return new DefaultCodeGenerator().generate(secret, counter);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String csrfFrom(String html) {
        Matcher m = CSRF_FIELD.matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("no _csrf field in page");
        }
        return m.group(1);
    }

    /** Form login + TOTP verification; returns the stepped-up session. */
    private SessionFilter login(String username, String mfaSecret) {
        SessionFilter session = new SessionFilter();
        String loginCsrf = csrfFrom(given().filter(session).get("/login").asString());
        given().filter(session).redirects().follow(false)
                .formParam("username", username)
                .formParam("password", PASS)
                .formParam("_csrf", loginCsrf)
                .when().post("/login")
                .then().statusCode(302).header("Location", endsWith("/admin"));
        String mfaCsrf = csrfFrom(given().filter(session).get("/mfa").asString());
        given().filter(session).redirects().follow(false)
                .formParam("code", totp(mfaSecret))
                .formParam("_csrf", mfaCsrf)
                .when().post("/mfa")
                .then().statusCode(302).header("Location", endsWith("/admin"));
        return session;
    }

    @Test
    void auditor_canOpenEveryViewPage() {
        SessionFilter session = login(auditorUser, auditorSecret);
        for (String path : List.of("/admin", "/admin/charges", "/admin/payments", "/admin/escrow-accounts",
                "/admin/consumers", "/admin/reconciliations", "/admin/webhooks", "/admin/audit")) {
            given().filter(session).when().get(path).then().statusCode(200);
        }
    }

    @Test
    void auditor_isForbiddenFromManagementSections() {
        SessionFilter session = login(auditorUser, auditorSecret);
        for (String path : List.of("/admin/operators", "/admin/roles", "/admin/bank-ip-rules")) {
            given().filter(session).when().get(path).then().statusCode(403);
        }
    }

    @Test
    void auditor_manageActionsAreForbiddenNotJustHidden() {
        SessionFilter session = login(auditorUser, auditorSecret);
        // A valid CSRF token, so a 403 can only come from authorization.
        String csrf = csrfFrom(given().filter(session).get("/change-password").asString());
        int n = SEQ.get();

        given().filter(session)
                .formParam("_csrf", csrf)
                .formParam("code", "rbac-http-c-" + n)
                .formParam("name", "Denied")
                .formParam("clientId", "rbac-http-ci-" + n)
                .formParam("clientSecret", "s3cret-value")
                .formParam("webhookUrl", "https://hook.example/denied")
                .formParam("status", "ACTIVE")
                .when().post("/admin/consumers")
                .then().statusCode(403);

        given().filter(session).formParam("_csrf", csrf)
                .when().post("/admin/escrow-accounts")
                .then().statusCode(403);

        given().filter(session).formParam("_csrf", csrf)
                .when().post("/admin/webhooks/no-such-id/replay")
                .then().statusCode(403);
    }

    @Test
    void admin_canPerformManageActions() {
        SessionFilter session = login(adminUser, adminSecret);
        String csrf = csrfFrom(given().filter(session).get("/change-password").asString());
        int n = SEQ.get();

        given().filter(session).redirects().follow(false)
                .formParam("_csrf", csrf)
                .formParam("code", "rbac-http-consumer-" + n)
                .formParam("name", "Created via RBAC test")
                .formParam("clientId", "rbac-http-client-" + n)
                .formParam("clientSecret", "s3cret-value")
                .formParam("webhookUrl", "https://hook.example/created")
                .formParam("status", "ACTIVE")
                .when().post("/admin/consumers")
                // success redirects to the list; a rejected create would bounce back to /new
                .then().statusCode(302).header("Location", endsWith("/admin/consumers"));
    }
}
