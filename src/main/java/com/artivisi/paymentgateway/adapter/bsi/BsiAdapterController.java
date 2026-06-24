package com.artivisi.paymentgateway.adapter.bsi;

import com.artivisi.paymentgateway.dto.InquiryResult;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.exception.InvalidPaymentException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.service.EscrowResolver;
import com.artivisi.paymentgateway.service.InquiryService;
import com.artivisi.paymentgateway.service.PaymentApplicationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;

/**
 * BSI adapter (SELF_HOSTED, proprietary REST/JSON). The bank calls this endpoint for inquiry
 * and payment notification. Resolves the escrow from the VA number, verifies the SHA-1 checksum
 * against the escrow's shared key, then drives the core inquiry/payment services and maps the
 * result back to BSI's wire format. Failures map to BSI response codes (never thrown to the bank).
 */
@RestController
@RequestMapping("/api/bank/bsi")
public class BsiAdapterController {

    private static final String PROVIDER = "bsi";

    private final EscrowResolver escrowResolver;
    private final InquiryService inquiryService;
    private final PaymentApplicationService paymentApplicationService;
    private final ChargeRepository chargeRepository;

    public BsiAdapterController(EscrowResolver escrowResolver, InquiryService inquiryService,
                                PaymentApplicationService paymentApplicationService,
                                ChargeRepository chargeRepository) {
        this.escrowResolver = escrowResolver;
        this.inquiryService = inquiryService;
        this.paymentApplicationService = paymentApplicationService;
        this.chargeRepository = chargeRepository;
    }

    @PostMapping
    public BsiResponse handle(@RequestBody BsiRequest request) {
        String action = request.action() == null ? "" : request.action().toLowerCase(Locale.ROOT);

        EscrowAccount escrow;
        try {
            escrow = escrowResolver.resolveForVaNumber(PROVIDER, request.nomorPembayaran());
        } catch (NotFoundException e) {
            return BsiResponse.error(BsiResponseCode.INVALID_ACCOUNT, e.getMessage(),
                    action, request.nomorPembayaran(), request.idTransaksi());
        }

        String expected = BsiChecksum.compute(
                request.nomorPembayaran(), escrow.getClientSecret(), request.tanggalTransaksi());
        if (!BsiChecksum.matches(expected, request.checksum())) {
            return BsiResponse.error(BsiResponseCode.INVALID_CHECKSUM, "invalid checksum",
                    action, request.nomorPembayaran(), request.idTransaksi());
        }

        return switch (action) {
            case "inquiry" -> handleInquiry(escrow, request);
            case "payment" -> handlePayment(escrow, request);
            default -> BsiResponse.error(BsiResponseCode.INVALID_ACTION,
                    "unsupported action: " + action, action, request.nomorPembayaran(), request.idTransaksi());
        };
    }

    private BsiResponse handleInquiry(EscrowAccount escrow, BsiRequest request) {
        try {
            InquiryResult result = inquiryService.inquire(escrow, request.nomorPembayaran());
            return new BsiResponse(BsiResponseCode.SUCCESS, "Success", "inquiry",
                    request.nomorPembayaran(), request.nomorInvoice(),
                    result.chargeType().name(), result.payerName(),
                    result.totalAmount(), result.remainingAmount(),
                    result.totalAmount().subtract(result.remainingAmount()),
                    null, request.idTransaksi());
        } catch (NotFoundException e) {
            return BsiResponse.error(BsiResponseCode.INVALID_ACCOUNT, e.getMessage(),
                    "inquiry", request.nomorPembayaran(), request.idTransaksi());
        }
    }

    private BsiResponse handlePayment(EscrowAccount escrow, BsiRequest request) {
        try {
            Payment payment = paymentApplicationService.apply(escrow, request.nomorPembayaran(),
                    request.nilai(), request.idTransaksi(), Instant.now());
            Charge charge = chargeRepository.findById(payment.getCharge().getId()).orElseThrow();
            return new BsiResponse(BsiResponseCode.SUCCESS, "Success", "payment",
                    request.nomorPembayaran(), request.nomorInvoice(),
                    charge.getChargeType().name(), charge.getPayerName(),
                    charge.getAmount(), charge.getAmount().subtract(charge.getCumulativePaid()),
                    charge.getCumulativePaid(), payment.getId(), request.idTransaksi());
        } catch (NotFoundException e) {
            return BsiResponse.error(BsiResponseCode.INVALID_ACCOUNT, e.getMessage(),
                    "payment", request.nomorPembayaran(), request.idTransaksi());
        } catch (InvalidPaymentException e) {
            return BsiResponse.error(BsiResponseCode.INVALID_AMOUNT, e.getMessage(),
                    "payment", request.nomorPembayaran(), request.idTransaksi());
        }
    }
}
