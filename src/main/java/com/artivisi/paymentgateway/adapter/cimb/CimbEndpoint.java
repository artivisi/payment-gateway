package com.artivisi.paymentgateway.adapter.cimb;

import com.artivisi.paymentgateway.adapter.cimb.xml.BillDetail;
import com.artivisi.paymentgateway.adapter.cimb.xml.InquiryRq;
import com.artivisi.paymentgateway.adapter.cimb.xml.InquiryRs;
import com.artivisi.paymentgateway.adapter.cimb.xml.PaymentRq;
import com.artivisi.paymentgateway.adapter.cimb.xml.PaymentRs;
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
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * CIMB adapter (SELF_HOSTED, SOAP/XML). The bank calls these operations for inquiry and payment
 * notification. Resolves the escrow from CustomerKey1 (the VA number) and drives the core
 * inquiry/payment services, mapping outcomes to CIMB response codes. Auth is HTTPS + IP allowlist
 * at the network layer (no in-message signature).
 */
@Endpoint
public class CimbEndpoint {

    private static final String PROVIDER = "cimb";
    private static final String IDR = "IDR";

    private final EscrowResolver escrowResolver;
    private final InquiryService inquiryService;
    private final PaymentApplicationService paymentApplicationService;
    private final ChargeRepository chargeRepository;

    public CimbEndpoint(EscrowResolver escrowResolver, InquiryService inquiryService,
                        PaymentApplicationService paymentApplicationService,
                        ChargeRepository chargeRepository) {
        this.escrowResolver = escrowResolver;
        this.inquiryService = inquiryService;
        this.paymentApplicationService = paymentApplicationService;
        this.chargeRepository = chargeRepository;
    }

    @PayloadRoot(namespace = CimbProtocol.NAMESPACE, localPart = CimbProtocol.INQUIRY_RQ)
    @ResponsePayload
    public InquiryRs inquiry(@RequestPayload InquiryRq request) {
        InquiryRs response = new InquiryRs();
        echo(response, request.getTransactionID(), request.getChannelID(), request.getTerminalID(),
                request.getTransactionDate(), request.getCompanyCode(), request.getCustomerKey1());
        response.setCurrency(IDR);
        response.setFee(BigDecimal.ZERO);
        try {
            EscrowAccount escrow = escrowResolver.resolveForVaNumber(PROVIDER, request.getCustomerKey1());
            InquiryResult result = inquiryService.inquire(escrow, request.getCustomerKey1());
            response.setCustomerName(result.payerName());
            response.setAmount(result.remainingAmount());
            response.setPaidAmount(result.totalAmount());
            response.setFlagPayment(result.chargeType() == ChargeType.CLOSED ? "1" : "0");
            response.setBillDetails(List.of(new BillDetail(
                    IDR, result.chargeType().name(), result.remainingAmount(), request.getCustomerKey1())));
            response.setResponseCode(CimbProtocol.RC_SUCCESS);
            response.setResponseDescription("Success");
        } catch (NotFoundException e) {
            response.setResponseCode(CimbProtocol.RC_NOT_FOUND);
            response.setResponseDescription(e.getMessage());
        } catch (RuntimeException e) {
            response.setResponseCode(CimbProtocol.RC_FAILURE);
            response.setResponseDescription(e.getMessage());
        }
        return response;
    }

    @PayloadRoot(namespace = CimbProtocol.NAMESPACE, localPart = CimbProtocol.PAYMENT_RQ)
    @ResponsePayload
    public PaymentRs payment(@RequestPayload PaymentRq request) {
        PaymentRs response = new PaymentRs();
        response.setTransactionID(request.getTransactionID());
        response.setChannelID(request.getChannelID());
        response.setTerminalID(request.getTerminalID());
        response.setTransactionDate(request.getTransactionDate());
        response.setCompanyCode(request.getCompanyCode());
        response.setCustomerKey1(request.getCustomerKey1());
        response.setCurrency(IDR);
        response.setFee(BigDecimal.ZERO);
        response.setPaymentFlag("100000");
        response.setReferenceNumberTransaction(request.getReferenceNumberTransaction());
        try {
            EscrowAccount escrow = escrowResolver.resolveForVaNumber(PROVIDER, request.getCustomerKey1());
            Payment payment = paymentApplicationService.apply(escrow, request.getCustomerKey1(),
                    request.getPaidAmount(), request.getReferenceNumberTransaction(), Instant.now());
            Charge charge = chargeRepository.findById(payment.getCharge().getId()).orElseThrow();
            response.setCustomerName(charge.getPayerName());
            response.setPaidAmount(request.getPaidAmount());
            response.setAmount(charge.getAmount().subtract(charge.getCumulativePaid()));
            response.setResponseCode(CimbProtocol.RC_SUCCESS);
            response.setResponseDescription("Success");
        } catch (NotFoundException e) {
            response.setResponseCode(CimbProtocol.RC_NOT_FOUND);
            response.setResponseDescription(e.getMessage());
        } catch (InvalidPaymentException e) {
            response.setResponseCode(CimbProtocol.RC_INVALID_AMOUNT);
            response.setResponseDescription(e.getMessage());
        } catch (RuntimeException e) {
            response.setResponseCode(CimbProtocol.RC_FAILURE);
            response.setResponseDescription(e.getMessage());
        }
        return response;
    }

    private void echo(InquiryRs response, String transactionId, String channelId, String terminalId,
                      String transactionDate, String companyCode, String customerKey1) {
        response.setTransactionID(transactionId);
        response.setChannelID(channelId);
        response.setTerminalID(terminalId);
        response.setTransactionDate(transactionDate);
        response.setCompanyCode(companyCode);
        response.setCustomerKey1(customerKey1);
    }
}
