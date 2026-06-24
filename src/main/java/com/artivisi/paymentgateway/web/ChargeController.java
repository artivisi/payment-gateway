package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.ChargeResponse;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.service.ChargeService;
import com.artivisi.paymentgateway.service.ConsumerAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer-facing charge API. Authenticated with {@code X-Client-Id} / {@code X-Client-Secret}.
 */
@RestController
@RequestMapping("/api/charges")
public class ChargeController {

    private static final String CLIENT_ID_HEADER = "X-Client-Id";
    private static final String CLIENT_SECRET_HEADER = "X-Client-Secret";

    private final ChargeService chargeService;
    private final ConsumerAuthService consumerAuthService;

    public ChargeController(ChargeService chargeService, ConsumerAuthService consumerAuthService) {
        this.chargeService = chargeService;
        this.consumerAuthService = consumerAuthService;
    }

    @PostMapping
    public ResponseEntity<ChargeResponse> create(
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @RequestHeader(value = CLIENT_SECRET_HEADER, required = false) String clientSecret,
            @Valid @RequestBody CreateChargeRequest request) {
        Consumer consumer = consumerAuthService.authenticate(clientId, clientSecret);
        ChargeService.CreateChargeOutcome outcome = chargeService.create(consumer, request);
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.response());
    }

    @GetMapping("/{id}")
    public ChargeResponse get(
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @RequestHeader(value = CLIENT_SECRET_HEADER, required = false) String clientSecret,
            @PathVariable String id) {
        Consumer consumer = consumerAuthService.authenticate(clientId, clientSecret);
        return chargeService.get(consumer, id);
    }

    @PostMapping("/{id}/cancel")
    public ChargeResponse cancel(
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @RequestHeader(value = CLIENT_SECRET_HEADER, required = false) String clientSecret,
            @PathVariable String id) {
        Consumer consumer = consumerAuthService.authenticate(clientId, clientSecret);
        return chargeService.cancel(consumer, id);
    }
}
