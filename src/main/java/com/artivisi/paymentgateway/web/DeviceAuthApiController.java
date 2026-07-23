package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.DeviceCodeResponse;
import com.artivisi.paymentgateway.dto.DeviceTokenResponse;
import com.artivisi.paymentgateway.entity.DeviceCode;
import com.artivisi.paymentgateway.service.DeviceAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * Device Authorization Grant endpoints (RFC 8628) — the only unauthenticated API surface, because
 * they are how a CLI obtains credentials in the first place.
 */
@RestController
@RequestMapping("/api/device")
public class DeviceAuthApiController {

    private final DeviceAuthService deviceAuthService;

    public DeviceAuthApiController(DeviceAuthService deviceAuthService) {
        this.deviceAuthService = deviceAuthService;
    }

    /** Step 1 — the CLI asks for a code and shows the user code to a human. */
    @PostMapping("/code")
    public DeviceCodeResponse requestCode(@RequestBody(required = false) Map<String, String> body,
                                          HttpServletRequest request) {
        Map<String, String> in = body == null ? Map.of() : body;
        DeviceCode code = deviceAuthService.requestCode(
                in.getOrDefault("clientId", "cli"), in.get("deviceName"));
        String base = baseUrl(request);
        return new DeviceCodeResponse(
                code.getDeviceCode(), code.getUserCode(),
                base + "/device", base + "/device?code=" + code.getUserCode(),
                java.time.Duration.between(java.time.Instant.now(), code.getExpiresAt()).toSeconds(),
                DeviceAuthService.POLL_INTERVAL_SECONDS);
    }

    /**
     * Step 2 — the CLI polls. RFC 8628 says a pending authorization is an OAuth error response, so
     * `authorization_pending` is HTTP 400 with an `error` field, not a 200 the caller must inspect.
     */
    @PostMapping("/token")
    public ResponseEntity<?> poll(@RequestBody Map<String, String> body) {
        DeviceAuthService.PollResult result = deviceAuthService.poll(body.get("deviceCode"));
        if (!result.issued()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", result.error()));
        }
        return ResponseEntity.ok(new DeviceTokenResponse(
                result.plaintextToken(), "Bearer",
                java.time.Duration.between(java.time.Instant.now(), result.token().getExpiresAt()).toSeconds(),
                result.token().getOperator().getUsername(),
                result.token().getDeviceName()));
    }

    private static String baseUrl(HttpServletRequest request) {
        URI uri = URI.create(request.getRequestURL().toString());
        String port = (uri.getPort() == -1 || uri.getPort() == 80 || uri.getPort() == 443)
                ? "" : ":" + uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + port;
    }
}
