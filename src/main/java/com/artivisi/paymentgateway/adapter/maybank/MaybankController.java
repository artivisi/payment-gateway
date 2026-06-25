package com.artivisi.paymentgateway.adapter.maybank;

import com.artivisi.paymentgateway.adapter.maybank.SnapMessages.Amount;
import com.artivisi.paymentgateway.adapter.maybank.SnapMessages.InquiryRequest;
import com.artivisi.paymentgateway.adapter.maybank.SnapMessages.InquiryResponse;
import com.artivisi.paymentgateway.adapter.maybank.SnapMessages.PaymentData;
import com.artivisi.paymentgateway.adapter.maybank.SnapMessages.PaymentRequest;
import com.artivisi.paymentgateway.adapter.maybank.SnapMessages.PaymentResponse;
import com.artivisi.paymentgateway.adapter.maybank.SnapMessages.TokenResponse;
import com.artivisi.paymentgateway.adapter.maybank.SnapMessages.VirtualAccountData;
import com.artivisi.paymentgateway.adapter.snap.SnapSignatureHelper;
import com.artivisi.paymentgateway.adapter.snap.SnapSpec;
import com.artivisi.paymentgateway.dto.InquiryResult;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.SnapAccessToken;
import com.artivisi.paymentgateway.exception.ConflictException;
import com.artivisi.paymentgateway.exception.InvalidPaymentException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.exception.UnauthorizedException;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import com.artivisi.paymentgateway.service.InquiryService;
import com.artivisi.paymentgateway.service.PaymentApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Maybank adapter (SELF_HOSTED, BI SNAP 1.0.2 REST/JSON). The gateway is the SNAP provider: the
 * bank requests a bearer token (RSA-signed), then calls inquiry/payment (HMAC-SHA512 signed).
 * See {@code docs/snap/snap-1.0.2.json}.
 */
@RestController
@RequestMapping("/api/bank/maybank")
public class MaybankController {

    private static final String IDR = "IDR";

    private final EscrowAccountRepository escrowAccountRepository;
    private final SnapTokenService tokenService;
    private final SnapRequestValidator requestValidator;
    private final InquiryService inquiryService;
    private final PaymentApplicationService paymentApplicationService;
    private final ObjectMapper objectMapper;

    public MaybankController(EscrowAccountRepository escrowAccountRepository, SnapTokenService tokenService,
                             SnapRequestValidator requestValidator, InquiryService inquiryService,
                             PaymentApplicationService paymentApplicationService, ObjectMapper objectMapper) {
        this.escrowAccountRepository = escrowAccountRepository;
        this.tokenService = tokenService;
        this.requestValidator = requestValidator;
        this.inquiryService = inquiryService;
        this.paymentApplicationService = paymentApplicationService;
        this.objectMapper = objectMapper;
    }

    @SnapSpec({"snap.sec.access-token.endpoint", "snap.sec.access-token.signature"})
    @PostMapping("/v1.0/access-token/b2b")
    public ResponseEntity<TokenResponse> token(
            @RequestHeader(value = "X-TIMESTAMP", required = false) String timestamp,
            @RequestHeader(value = "X-CLIENT-KEY", required = false) String clientKey,
            @RequestHeader(value = "X-SIGNATURE", required = false) String signature) {
        EscrowAccount escrow = clientKey == null ? null
                : escrowAccountRepository.findByClientId(clientKey).orElse(null);
        if (escrow == null
                || !SnapSignatureHelper.verifyToken(escrow.getPublicKey(), clientKey, timestamp, signature)) {
            return ResponseEntity.status(SnapResponseCode.httpStatus(SnapResponseCode.TOKEN_UNAUTHORIZED))
                    .body(new TokenResponse(SnapResponseCode.TOKEN_UNAUTHORIZED, "Unauthorized signature",
                            null, null, null));
        }
        SnapAccessToken token = tokenService.issue(escrow);
        return ResponseEntity.ok(new TokenResponse(SnapResponseCode.TOKEN_SUCCESS, "Successful",
                token.getAccessToken(), "Bearer", String.valueOf(tokenService.ttlSeconds())));
    }

    @SnapSpec({"snap.data.va.inquiry", "snap.sec.transaction.signature.symmetric", "snap.sec.external-id"})
    @PostMapping("/v1.0/transfer-va/inquiry")
    public ResponseEntity<InquiryResponse> inquiry(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-TIMESTAMP", required = false) String timestamp,
            @RequestHeader(value = "X-SIGNATURE", required = false) String signature,
            @RequestHeader(value = "X-EXTERNAL-ID", required = false) String externalId,
            @RequestBody String rawBody, HttpServletRequest httpRequest) {
        try {
            EscrowAccount escrow = requestValidator.authorize(authorization, timestamp, signature, externalId,
                    "POST", httpRequest.getRequestURI(), rawBody, "inquiry");
            InquiryRequest request = objectMapper.readValue(rawBody, InquiryRequest.class);
            InquiryResult result = inquiryService.inquire(escrow, normalize(request.virtualAccountNo()));
            VirtualAccountData data = new VirtualAccountData(request.partnerServiceId(), request.customerNo(),
                    request.virtualAccountNo(), result.payerName(), request.inquiryRequestId(),
                    new Amount(format(result.totalAmount()), IDR), "00");
            return ResponseEntity.ok(new InquiryResponse(SnapResponseCode.INQUIRY_SUCCESS, "Successful", data));
        } catch (UnauthorizedException e) {
            return inquiryError(SnapResponseCode.INQUIRY_UNAUTHORIZED, e.getMessage());
        } catch (ConflictException e) {
            return inquiryError(SnapResponseCode.INQUIRY_CONFLICT, e.getMessage());
        } catch (NotFoundException e) {
            return inquiryError(SnapResponseCode.INQUIRY_NOT_FOUND, e.getMessage());
        }
    }

    @SnapSpec({"snap.data.va.payment", "snap.sec.transaction.signature.symmetric"})
    @PostMapping("/v1.0/transfer-va/payment")
    public ResponseEntity<PaymentResponse> payment(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-TIMESTAMP", required = false) String timestamp,
            @RequestHeader(value = "X-SIGNATURE", required = false) String signature,
            @RequestHeader(value = "X-EXTERNAL-ID", required = false) String externalId,
            @RequestBody String rawBody, HttpServletRequest httpRequest) {
        try {
            EscrowAccount escrow = requestValidator.authorize(authorization, timestamp, signature, externalId,
                    "POST", httpRequest.getRequestURI(), rawBody, "payment");
            PaymentRequest request = objectMapper.readValue(rawBody, PaymentRequest.class);
            BigDecimal paid = new BigDecimal(request.paidAmount().value());
            paymentApplicationService.apply(escrow, normalize(request.virtualAccountNo()),
                    paid, request.referenceNo(), Instant.now());
            PaymentData data = new PaymentData(request.partnerServiceId(), request.customerNo(),
                    request.virtualAccountNo(), request.paymentRequestId(),
                    new Amount(format(paid), IDR), request.referenceNo(), "00");
            return ResponseEntity.ok(new PaymentResponse(SnapResponseCode.PAYMENT_SUCCESS, "Successful", data));
        } catch (UnauthorizedException e) {
            return paymentError(SnapResponseCode.PAYMENT_UNAUTHORIZED, e.getMessage());
        } catch (ConflictException e) {
            return paymentError(SnapResponseCode.PAYMENT_CONFLICT, e.getMessage());
        } catch (NotFoundException e) {
            return paymentError(SnapResponseCode.PAYMENT_NOT_FOUND, e.getMessage());
        } catch (InvalidPaymentException e) {
            return paymentError(SnapResponseCode.PAYMENT_INVALID_AMOUNT, e.getMessage());
        }
    }

    private ResponseEntity<InquiryResponse> inquiryError(String code, String message) {
        return ResponseEntity.status(SnapResponseCode.httpStatus(code))
                .body(new InquiryResponse(code, message, null));
    }

    private ResponseEntity<PaymentResponse> paymentError(String code, String message) {
        return ResponseEntity.status(SnapResponseCode.httpStatus(code))
                .body(new PaymentResponse(code, message, null));
    }

    private static String normalize(String virtualAccountNo) {
        return virtualAccountNo == null ? null : virtualAccountNo.replaceAll("\\s", "");
    }

    private static String format(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
