package com.artivisi.paymentgateway.exception;

/**
 * The number allocated for this payer and slot is already held ACTIVE at that escrow by another
 * charge.
 *
 * <p>A structured conflict rather than a sentence, because a consumer has to act on it and can no
 * longer work any of it out for itself. It does not compose the number any more, so the number is
 * here; and the only move available to it — supersede the bill holding the number, or wait for that
 * bill to be paid — needs the reference of that bill, so that is here too whenever the holding
 * charge is the same consumer's.
 *
 * <p>{@code heldByConsumerReference} is null when the holding charge belongs to a different
 * consumer. One consumer's bill ids are not another's to read, and a clash across consumers on a
 * shared escrow is an operator's problem; the escrow and the number are enough to report it.
 */
public class VaNumberBusyException extends DuplicateException {

    private final String escrowCode;
    private final String vaNumber;
    private final String heldByConsumerReference;

    public VaNumberBusyException(String escrowCode, String vaNumber, String heldByConsumerReference) {
        super("vaNumber already active for escrow " + escrowCode + ": " + vaNumber
                + (heldByConsumerReference == null ? "" : " (held by charge " + heldByConsumerReference + ")"));
        this.escrowCode = escrowCode;
        this.vaNumber = vaNumber;
        this.heldByConsumerReference = heldByConsumerReference;
    }

    public String getEscrowCode() {
        return escrowCode;
    }

    public String getVaNumber() {
        return vaNumber;
    }

    public String getHeldByConsumerReference() {
        return heldByConsumerReference;
    }
}
