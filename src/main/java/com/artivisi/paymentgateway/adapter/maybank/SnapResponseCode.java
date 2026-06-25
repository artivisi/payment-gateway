package com.artivisi.paymentgateway.adapter.maybank;

/** SNAP 7-digit response codes ([HTTP:3][service:2][case:2]). VA service 24=inquiry, 25=payment. */
public final class SnapResponseCode {

    public static final String TOKEN_SUCCESS = "2007300";
    public static final String TOKEN_UNAUTHORIZED = "4017300";

    public static final String INQUIRY_SUCCESS = "2002400";
    public static final String INQUIRY_UNAUTHORIZED = "4012400";
    public static final String INQUIRY_NOT_FOUND = "4042412";
    public static final String INQUIRY_CONFLICT = "4092400";

    public static final String PAYMENT_SUCCESS = "2002500";
    public static final String PAYMENT_UNAUTHORIZED = "4012500";
    public static final String PAYMENT_NOT_FOUND = "4042512";
    public static final String PAYMENT_INVALID_AMOUNT = "4042513";
    public static final String PAYMENT_CONFLICT = "4092500";

    private SnapResponseCode() {
    }

    /** HTTP status carried in the first three digits of a SNAP response code. */
    public static int httpStatus(String responseCode) {
        return Integer.parseInt(responseCode.substring(0, 3));
    }
}
