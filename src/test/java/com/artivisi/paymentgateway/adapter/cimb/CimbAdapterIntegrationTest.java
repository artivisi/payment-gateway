package com.artivisi.paymentgateway.adapter.cimb;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.service.ChargeService;
import com.artivisi.paymentgateway.service.ConsumerService;
import com.artivisi.paymentgateway.service.EscrowAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class CimbAdapterIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;

    private String vaNumber;

    private static EscrowAccountRequest escrowRequest(String code) {
        return new EscrowAccountRequest(code, "cimb", HostingModel.SELF_HOSTED, TransportProtocol.SOAP_XML,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "920900111", "Operator Settlement", "92099", "920", 10, null, null);
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        String escrowCode = "cimb-adp-" + n;
        vaNumber = "9200000" + String.format("%03d", n);
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "cimbadp-consumer-" + n, "Academic", "cimbadp-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        escrowService.create(escrowRequest(escrowCode));
        chargeService.create(consumer, new CreateChargeRequest(
                "cimbadp-ref-" + n, "Student", null, null, ChargeType.CLOSED, new BigDecimal("1000000"), null,
                List.of(new ChargeAccountRequest(escrowCode, vaNumber))));
    }

    private static String inquiryEnvelope(String va) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                  <soapenv:Body>
                    <m:CIMB3rdParty_InquiryRq xmlns:m="http://CIMB3rdParty/BillPaymentWS">
                      <TransactionID>TRX-%s</TransactionID>
                      <ChannelID>01</ChannelID>
                      <TerminalID>T1</TerminalID>
                      <TransactionDate>20260625120000</TransactionDate>
                      <CompanyCode>CC</CompanyCode>
                      <CustomerKey1>%s</CustomerKey1>
                    </m:CIMB3rdParty_InquiryRq>
                  </soapenv:Body>
                </soapenv:Envelope>""".formatted(va, va);
    }

    private static String paymentEnvelope(String va, String paidAmount, String reference) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                  <soapenv:Body>
                    <m:CIMB3rdParty_PaymentRq xmlns:m="http://CIMB3rdParty/BillPaymentWS">
                      <TransactionID>TRX-%s</TransactionID>
                      <ChannelID>01</ChannelID>
                      <TerminalID>T1</TerminalID>
                      <TransactionDate>20260625120000</TransactionDate>
                      <CompanyCode>CC</CompanyCode>
                      <CustomerKey1>%s</CustomerKey1>
                      <Currency>IDR</Currency>
                      <PaidAmount>%s</PaidAmount>
                      <ReferenceNumberTransaction>%s</ReferenceNumberTransaction>
                    </m:CIMB3rdParty_PaymentRq>
                  </soapenv:Body>
                </soapenv:Envelope>""".formatted(va, va, paidAmount, reference);
    }

    private String post(String soap) {
        return given().contentType("text/xml; charset=utf-8").header("SOAPAction", "").body(soap)
                .when().post("/ws/cimb/")
                .then().statusCode(200).extract().asString();
    }

    @Test
    void inquiry_returnsBillDetails() {
        String xml = post(inquiryEnvelope(vaNumber));
        assertThat(xml).contains("<ResponseCode>00</ResponseCode>");
        assertThat(xml).contains("<CustomerName>Student</CustomerName>");
        assertThat(xml).contains("<FlagPayment>1</FlagPayment>");
    }

    @Test
    void payment_settlesThenInquiryIsNotFound() {
        String pay = post(paymentEnvelope(vaNumber, "1000000", "CIMB-REF-1"));
        assertThat(pay).contains("<ResponseCode>00</ResponseCode>");

        String inquiry = post(inquiryEnvelope(vaNumber));
        assertThat(inquiry).contains("<ResponseCode>16</ResponseCode>");
    }

    @Test
    void unknownVa_isNotFound() {
        String xml = post(inquiryEnvelope("9209999999"));
        assertThat(xml).contains("<ResponseCode>16</ResponseCode>");
    }

    @Test
    void closedWrongAmount_isInvalidAmount() {
        String xml = post(paymentEnvelope(vaNumber, "999", "CIMB-REF-1"));
        assertThat(xml).contains("<ResponseCode>38</ResponseCode>");
    }
}
