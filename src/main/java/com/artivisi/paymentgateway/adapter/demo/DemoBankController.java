package com.artivisi.paymentgateway.adapter.demo;

import com.artivisi.paymentgateway.adapter.spi.BankAdapter;
import com.artivisi.paymentgateway.dto.InquiryResult;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.exception.InvalidPaymentException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.service.BankCallbackRecorder;
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
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * A complete adapter for an invented bank, and the only one this repository ships.
 *
 * <p>It exists so the engine can be read, tested and demonstrated end to end without publishing any
 * bank's integration specification. Everything a real adapter must get right is here, and each is
 * a lesson paid for in production:
 *
 * <ul>
 *   <li><b>Record the raw message before parsing it.</b> A typed binding silently discards every
 *       field it does not model, and a field nobody modelled is exactly the one finance asks about
 *       later.</li>
 *   <li><b>Authenticate before doing anything.</b> Resolve the escrow first, because the shared
 *       secret lives on it, then check the signature; an adapter that acts first and checks after is
 *       an open payment endpoint.</li>
 *   <li><b>Answer in the bank's vocabulary, never in ours.</b> Core throws
 *       {@link NotFoundException} or {@link InvalidPaymentException}; the bank's switch understands
 *       response codes. Letting an exception escape as an HTTP 500 tells the terminal nothing and
 *       usually makes it retry.</li>
 *   <li><b>Parse the bank's clock, and refuse what you cannot parse.</b> The settlement file is
 *       matched on the bank's own transaction time, so substituting our own arrival time quietly
 *       misfiles payments around midnight and month end.</li>
 * </ul>
 *
 * <p>What it does not show is the {@code BANK_HOSTED} half — creating and cancelling a VA at the
 * bank — because the engine does not implement it yet either. Inventing a demonstration of an
 * unimplemented path would be the one kind of documentation worse than none.
 */
@RestController
@RequestMapping(DemoBankController.PATH)
public class DemoBankController implements BankAdapter {

    static final String PATH = "/api/bank/demo";
    private static final String PROVIDER = "demo";
    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final EscrowResolver escrowResolver;
    private final InquiryService inquiryService;
    private final PaymentApplicationService paymentApplicationService;
    private final BankCallbackRecorder callbackRecorder;

    public DemoBankController(EscrowResolver escrowResolver, InquiryService inquiryService,
                              PaymentApplicationService paymentApplicationService,
                              BankCallbackRecorder callbackRecorder) {
        this.escrowResolver = escrowResolver;
        this.inquiryService = inquiryService;
        this.paymentApplicationService = paymentApplicationService;
        this.callbackRecorder = callbackRecorder;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public HostingModel hostingModel() {
        return HostingModel.SELF_HOSTED;
    }

    @Override
    public String inboundPath() {
        return PATH;
    }

    @PostMapping
    public DemoBankMessages.Response handle(@RequestBody String rawBody) {
        DemoBankMessages.Request request =
                callbackRecorder.record(PROVIDER, rawBody, DemoBankMessages.Request.class);

        EscrowAccount escrow;
        try {
            escrow = escrowResolver.resolveForVaNumber(PROVIDER, request.accountNumber());
        } catch (NotFoundException e) {
            return DemoBankMessages.Response.error(DemoBankMessages.Code.UNKNOWN_ACCOUNT, e.getMessage());
        }
        if (!DemoBankSignature.matches(request, escrow.getClientSecret())) {
            return DemoBankMessages.Response.error(
                    DemoBankMessages.Code.INVALID_SIGNATURE, "Invalid signature");
        }

        String action = request.action() == null ? "" : request.action().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "inquiry" -> inquiry(escrow, request);
            case "payment" -> payment(escrow, request);
            default -> DemoBankMessages.Response.error(
                    DemoBankMessages.Code.UNKNOWN_ACTION, "Unknown action: " + action);
        };
    }

    private DemoBankMessages.Response inquiry(EscrowAccount escrow, DemoBankMessages.Request request) {
        try {
            InquiryResult result = inquiryService.inquire(escrow, request.accountNumber());
            return new DemoBankMessages.Response(DemoBankMessages.Code.OK, "OK",
                    result.vaNumber(), result.billNumber(), result.payerName(), result.description(),
                    result.totalAmount(), result.remainingAmount(), null);
        } catch (NotFoundException e) {
            // An expired or cancelled bill is "no such account" to the bank, not an error of ours.
            return DemoBankMessages.Response.error(DemoBankMessages.Code.UNKNOWN_ACCOUNT, e.getMessage());
        }
    }

    private DemoBankMessages.Response payment(EscrowAccount escrow, DemoBankMessages.Request request) {
        Instant transactionTime;
        try {
            transactionTime = LocalDateTime.parse(request.transactionTime(), TIMESTAMP)
                    .atZone(ZONE).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            return DemoBankMessages.Response.error(DemoBankMessages.Code.INVALID_AMOUNT,
                    "transactionTime must be yyyyMMddHHmmss: " + request.transactionTime());
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(request.amount());
        } catch (NumberFormatException | NullPointerException e) {
            return DemoBankMessages.Response.error(DemoBankMessages.Code.INVALID_AMOUNT,
                    "amount is not a number: " + request.amount());
        }
        try {
            Payment payment = paymentApplicationService.apply(escrow, request.accountNumber(), amount,
                    request.transactionId(), transactionTime);
            return new DemoBankMessages.Response(DemoBankMessages.Code.OK, "OK",
                    request.accountNumber(), payment.getCharge().getBillNumber(),
                    payment.getCharge().getPayerName(), payment.getCharge().getDescription(),
                    payment.getCharge().getAmount(),
                    payment.getCharge().getAmount().subtract(payment.getCharge().getCumulativePaid()),
                    payment.getId());
        } catch (NotFoundException e) {
            return DemoBankMessages.Response.error(DemoBankMessages.Code.UNKNOWN_ACCOUNT, e.getMessage());
        } catch (InvalidPaymentException e) {
            // Wrong amount on a closed bill, a bill already paid, a cancelled one: all the bank's
            // "amount not acceptable", all refused rather than half-accepted.
            return DemoBankMessages.Response.error(DemoBankMessages.Code.INVALID_AMOUNT, e.getMessage());
        }
    }
}
