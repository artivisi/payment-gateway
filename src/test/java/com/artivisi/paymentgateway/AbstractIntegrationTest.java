package com.artivisi.paymentgateway;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Base64;

/**
 * Boots the full app on a random port against a real PostgreSQL 18 container.
 *
 * <p>The container is a JVM-wide singleton started once in a static initializer (NOT managed by
 * {@code @Testcontainers}/{@code @Container}, which would stop it after the first test class and
 * leave the cached Spring context pointing at a dead database). Ryuk reaps it on JVM exit.
 *
 * <p>Datasource and the required secret key are injected here, so the production
 * {@code application.yml} placeholders never resolve in tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    protected int port;

    @org.springframework.beans.factory.annotation.Autowired
    private com.artivisi.paymentgateway.service.DeviceAuthService deviceAuthService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.artivisi.paymentgateway.repository.OperatorRepository operatorRepository;

    /**
     * A bearer token for the management API, carrying the bootstrap operator's permissions.
     *
     * <p>The management endpoints under {@code /api/escrow-accounts} and {@code /api/consumers} stopped
     * being open on 2026-08-25 — they had been reachable unauthenticated from the internet. Tests that
     * exercise them now have to authenticate the way any other caller does, which is the point: a test
     * that could reach them without credentials was, in effect, asserting the hole.
     */
    protected String managementToken() {
        var start = io.restassured.RestAssured.given().contentType("application/json")
                .body(java.util.Map.of("clientId", "integration-test", "deviceName", "tests"))
                .when().post("/api/device/code").then().statusCode(200).extract().jsonPath();
        deviceAuthService.authorize(start.getString("userCode"), operatorRepository.findAll().getFirst());
        return io.restassured.RestAssured.given().contentType("application/json")
                .body(java.util.Map.of("deviceCode", start.getString("deviceCode")))
                .when().post("/api/device/token").then().statusCode(200)
                .extract().path("accessToken");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("gateway.security.secret-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        // Effectively disable the periodic webhook poller; tests drive dispatchDue() explicitly.
        registry.add("gateway.webhook.poll-interval-ms", () -> "3600000");
        // Disable background expiry sweep; tests call ExpiryReaper directly.
        registry.add("gateway.reaper.interval-ms", () -> "3600000");
        // Bootstrap admin (required config) — fail-loud placeholders would otherwise block startup.
        registry.add("gateway.admin.bootstrap.username", () -> "bootstrap-admin");
        registry.add("gateway.admin.bootstrap.password", () -> "bootstrap-pass-0001");
    }

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
    }
}
