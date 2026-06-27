package com.artivisi.paymentgateway.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * The outbound webhook delivery client. Connection limits are pinned explicitly rather than inherited
 * from reactor-netty's defaults (which would leave the pool size and a 45s pending-acquire timeout
 * implicit) — consistent with the fail-loud, no-hidden-defaults principle. reactor-netty pools per
 * remote host, so {@code maxConnectionsPerHost} bounds connections to any single consumer endpoint;
 * combined with the per-consumer dispatch bulkhead, one slow endpoint cannot exhaust shared capacity.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(WebhookProperties properties) {
        WebhookProperties.Http http = properties.http();

        ConnectionProvider connectionProvider = ConnectionProvider.builder("webhook")
                .maxConnections(http.maxConnectionsPerHost())
                .pendingAcquireTimeout(Duration.ofMillis(http.pendingAcquireTimeoutMs()))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, http.connectTimeoutMs())
                .responseTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds()));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
