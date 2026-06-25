package com.artivisi.paymentgateway.adapter.maybank;

/** SNAP request/response payloads for the VA token + transfer-va services. */
public final class SnapMessages {

    private SnapMessages() {
    }

    public record Amount(String value, String currency) {
    }

    public record TokenResponse(String responseCode, String responseMessage,
                                String accessToken, String tokenType, String expiresIn) {
    }

    public record InquiryRequest(String partnerServiceId, String customerNo,
                                 String virtualAccountNo, String inquiryRequestId) {
    }

    public record VirtualAccountData(String partnerServiceId, String customerNo, String virtualAccountNo,
                                     String virtualAccountName, String inquiryRequestId,
                                     Amount totalAmount, String inquiryStatus) {
    }

    public record InquiryResponse(String responseCode, String responseMessage,
                                  VirtualAccountData virtualAccountData) {
    }

    public record PaymentRequest(String partnerServiceId, String customerNo, String virtualAccountNo,
                                 String paymentRequestId, Amount paidAmount, String referenceNo) {
    }

    public record PaymentData(String partnerServiceId, String customerNo, String virtualAccountNo,
                              String paymentRequestId, Amount paidAmount, String referenceNo,
                              String paymentFlagStatus) {
    }

    public record PaymentResponse(String responseCode, String responseMessage,
                                  PaymentData virtualAccountData) {
    }
}
