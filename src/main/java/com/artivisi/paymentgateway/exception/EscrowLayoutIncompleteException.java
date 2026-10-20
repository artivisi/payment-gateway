package com.artivisi.paymentgateway.exception;

/**
 * The escrow's number layout is missing a field, or leaves no room for a payer key, so the gateway
 * cannot allocate through it.
 *
 * <p>Nothing the consumer sends can fix this, which is why it carries its own code: a consumer
 * must not report it to its own sender as a bad request. It is an operator's to resolve, on the
 * escrow named here.
 */
public class EscrowLayoutIncompleteException extends InvalidRequestException {

    private final String escrowCode;

    public EscrowLayoutIncompleteException(String escrowCode, String message) {
        super(message);
        this.escrowCode = escrowCode;
    }

    public String getEscrowCode() {
        return escrowCode;
    }
}
