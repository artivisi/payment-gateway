package com.artivisi.paymentgateway.exception;

/** A syntactically valid request that violates a domain rule (e.g. VA number outside the escrow space). */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
