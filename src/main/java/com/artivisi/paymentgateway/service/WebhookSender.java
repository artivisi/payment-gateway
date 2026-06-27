package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.config.WebhookProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Stateless HTTP sender for a single webhook delivery. No DB access — signs the payload and POSTs
 * it, returning a {@link WebhookSendResult}. Runs on a virtual thread off the dispatcher; the
 * request timeout bounds how long one slow endpoint can hold its lane.
 */
@Component
public class WebhookSender {

    private static final String SIGNATURE_HEADER = "X-Signature";
    private static final String EVENT_HEADER = "X-Webhook-Event";
    private static final String ID_HEADER = "X-Webhook-Id";

    private final WebClient webClient;
    private final WebhookProperties properties;

    public WebhookSender(WebClient webClient, WebhookProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    public WebhookSendResult send(WebhookSendTask task) {
        String signature = sign(task.payload(), task.secret());
        try {
            webClient.post().uri(task.targetUrl())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(EVENT_HEADER, task.eventType())
                    .header(ID_HEADER, task.deliveryId())
                    .header(SIGNATURE_HEADER, signature)
                    .bodyValue(task.payload())
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(properties.requestTimeoutSeconds()));
            return new WebhookSendResult(true, 200, null);
        } catch (WebClientResponseException e) {
            return new WebhookSendResult(false, e.getStatusCode().value(), "HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            return new WebhookSendResult(false, null, truncate(e.getMessage()));
        }
    }

    private String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign webhook payload", e);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
