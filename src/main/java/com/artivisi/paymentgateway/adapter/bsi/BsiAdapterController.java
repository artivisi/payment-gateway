package com.artivisi.paymentgateway.adapter.bsi;

import com.artivisi.paymentgateway.dto.InquiryResult;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeType;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * BSI adapter (SELF_HOSTED, proprietary REST/JSON). The bank calls this endpoint for inquiry,
 * payment notification, and reversal. Resolves the escrow from the VA number, verifies the SHA-1
 * checksum against the escrow's shared key, then drives the core services and maps the result back
 * to BSI's wire format. Failures map to BSI response codes (never thrown to the bank).
 *
 * <p>The response shape mirrors legacy bsm-makara's {@code MakaraResponse} field-for-field: it echoes
 * the request's {@code kodeBank}/{@code kodeChannel}/{@code kodeTerminal}/{@code idTransaksi}, carries
 * {@code keterangan} (bill description), formats {@code tanggalTransaksi} as ISO date-time on
 * payment/reversal, and omits {@code akumulasiPembayaran}/reprices {@code tagihanEfektif} for OPEN —
 * exactly as bsm-makara does. {@code BsiResponse}'s {@code NON_EMPTY} serialization drops the rest.
 */
@RestController
@RequestMapping("/api/bank/bsi")
public class BsiAdapterController {

    private static final String PROVIDER = "bsi";
    /** bsm-makara runs in Asia/Jakarta and formats transaction times as a local ISO date-time. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

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
            return BsiResponse.error(BsiResponseCode.INVALID_ACCOUNT, e.getMessage());
        }

        String expected = BsiChecksum.compute(
                request.nomorPembayaran(), escrow.getClientSecret(), request.tanggalTransaksi());
        if (!BsiChecksum.matches(expected, request.checksum())) {
            return BsiResponse.error(BsiResponseCode.INVALID_CHECKSUM, "Invalid Checksum");
        }

        return switch (action) {
            case "inquiry" -> handleInquiry(escrow, request);
            case "payment" -> handlePayment(escrow, request);
            case "reversal" -> handleReversal(escrow, request);
            default -> BsiResponse.error(BsiResponseCode.INVALID_ACTION, "Action " + action + " tidak dikenal");
        };
    }

    private BsiResponse handleInquiry(EscrowAccount escrow, BsiRequest request) {
        InquiryResult result;
        try {
            result = inquiryService.inquire(escrow, request.nomorPembayaran());
        } catch (NotFoundException e) {
            return BsiResponse.error(BsiResponseCode.INVALID_ACCOUNT, e.getMessage());
        }
        boolean open = result.chargeType() == ChargeType.OPEN;
        BigDecimal cumulative = result.totalAmount().subtract(result.remainingAmount());
        return echo(request)
                .responseCode(BsiResponseCode.SUCCESS).responseMessage("OK")
                .nomorPembayaran(request.nomorPembayaran())
                .nomorInvoice(result.consumerReference())
                .jenisAkun(result.chargeType().name())
                .nama(result.payerName())
                .keterangan(result.description())
                .tagihanTotal(result.totalAmount())
                .tagihanEfektif(open ? result.totalAmount() : result.remainingAmount())
                .akumulasiPembayaran(open ? null : cumulative)
                .tanggalTransaksi(request.tanggalTransaksi())
                .build();
    }

    private BsiResponse handlePayment(EscrowAccount escrow, BsiRequest request) {
        Payment payment;
        try {
            payment = paymentApplicationService.apply(escrow, request.nomorPembayaran(),
                    request.nilai(), request.idTransaksi(), Instant.now());
        } catch (NotFoundException e) {
            return BsiResponse.error(BsiResponseCode.INVALID_ACCOUNT, e.getMessage());
        } catch (InvalidPaymentException e) {
            return BsiResponse.error(BsiResponseCode.INVALID_AMOUNT, e.getMessage());
        }
        Charge charge = chargeRepository.findById(payment.getCharge().getId()).orElseThrow();
        boolean open = charge.getChargeType() == ChargeType.OPEN;
        return echo(request)
                .responseCode(BsiResponseCode.SUCCESS).responseMessage("OK")
                .nomorPembayaran(request.nomorPembayaran())
                .nomorInvoice(charge.getConsumerReference())
                .jenisAkun(charge.getChargeType().name())
                .nama(charge.getPayerName())
                .keterangan(charge.getDescription())
                .tagihanTotal(charge.getAmount())
                .tagihanEfektif(open ? charge.getAmount() : charge.getAmount().subtract(charge.getCumulativePaid()))
                .akumulasiPembayaran(open ? null : charge.getCumulativePaid())
                .referensiPembayaran(payment.getId())
                .tanggalTransaksi(isoDateTime(payment.getTransactionTime()))
                .build();
    }

    private BsiResponse handleReversal(EscrowAccount escrow, BsiRequest request) {
        Payment payment;
        try {
            payment = paymentApplicationService.reverse(escrow, request.nomorPembayaran(),
                    request.idTransaksi(), request.nilai(), Instant.now());
        } catch (NotFoundException e) {
            return BsiResponse.error(BsiResponseCode.INVALID_ACCOUNT, e.getMessage());
        } catch (InvalidPaymentException e) {
            return BsiResponse.error(BsiResponseCode.INVALID_AMOUNT, e.getMessage());
        }
        Charge charge = chargeRepository.findById(payment.getCharge().getId()).orElseThrow();
        return echo(request)
                .responseCode(BsiResponseCode.SUCCESS).responseMessage("OK")
                .nomorPembayaran(request.nomorPembayaran())
                .nomorInvoice(charge.getConsumerReference())
                .nama(charge.getPayerName())
                .keterangan(charge.getDescription())
                .referensiPembayaran(payment.getId())
                .tanggalTransaksi(isoDateTime(Instant.now()))
                .build();
    }

    /** Seeds a response builder with the request-echo fields common to every success reply. */
    private static BsiResponse.BsiResponseBuilder echo(BsiRequest request) {
        return BsiResponse.builder()
                .kodeBank(request.kodeBank())
                .kodeChannel(request.kodeChannel())
                .kodeTerminal(request.kodeTerminal())
                .idTransaksi(request.idTransaksi());
    }

    private static String isoDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE).format(DateTimeFormatter.ISO_DATE_TIME);
    }
}
