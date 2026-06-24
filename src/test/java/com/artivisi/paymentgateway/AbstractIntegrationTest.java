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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("gateway.security.secret-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
    }
}
