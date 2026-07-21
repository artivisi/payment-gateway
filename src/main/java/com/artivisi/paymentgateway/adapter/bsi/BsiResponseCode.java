package com.artivisi.paymentgateway.adapter.bsi;

/** BSI proprietary response codes. */
public final class BsiResponseCode {

    public static final String SUCCESS = "00";
    public static final String INVALID_ACCOUNT = "03";
    public static final String INVALID_ACTION = "12";
    public static final String INVALID_AMOUNT = "13";
    public static final String INVALID_CHECKSUM = "25";
    public static final String INVALID_REQUEST_FORMAT = "30";
    public static final String ERROR = "99";

    private BsiResponseCode() {
    }
}
