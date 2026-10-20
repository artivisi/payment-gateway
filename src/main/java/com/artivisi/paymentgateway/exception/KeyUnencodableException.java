package com.artivisi.paymentgateway.exception;

/**
 * A {@code payerKey} or {@code slotKey} that cannot be placed in an escrow's number space: not
 * numeric, or longer than the segment the layout leaves for it.
 *
 * <p>Structured, like {@link VaNumberBusyException}, because the consumer has to tell this refusal
 * apart from every other 400. A payer key that does not fit is a fact about the request — the
 * consumer answers its own sender with it — whereas the same status for an escrow with no layout is
 * a fault on the gateway's side that only an operator can fix. Telling them apart by reading the
 * sentence would make the wording part of the contract.
 */
public class KeyUnencodableException extends InvalidRequestException {

    private final String field;
    private final String escrowCode;

    public KeyUnencodableException(String field, String escrowCode, String message) {
        super(message);
        this.field = field;
        this.escrowCode = escrowCode;
    }

    /** {@code payerKey} or {@code slotKey}. */
    public String getField() {
        return field;
    }

    public String getEscrowCode() {
        return escrowCode;
    }
}
