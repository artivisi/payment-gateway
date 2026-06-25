package com.artivisi.paymentgateway.adapter.cimb;

/** CIMB SOAP namespace, operation local parts, and response codes. */
public final class CimbProtocol {

    public static final String NAMESPACE = "http://CIMB3rdParty/BillPaymentWS";
    public static final String INQUIRY_RQ = "CIMB3rdParty_InquiryRq";
    public static final String PAYMENT_RQ = "CIMB3rdParty_PaymentRq";

    public static final String RC_SUCCESS = "00";
    public static final String RC_NOT_FOUND = "16";
    public static final String RC_ALREADY_PAID = "33";
    public static final String RC_INVALID_AMOUNT = "38";
    public static final String RC_FAILURE = "99";

    private CimbProtocol() {
    }
}
