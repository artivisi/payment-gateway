package com.artivisi.paymentgateway.exception;

/**
 * A payment that cannot be applied as-is: wrong amount for a CLOSED charge, overpayment,
 * payment against a cancelled/expired/paid charge. Never silently accepted (fail loud).
 */
public class InvalidPaymentException extends RuntimeException {
    public InvalidPaymentException(String message) {
        super(message);
    }
}
