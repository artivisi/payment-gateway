package com.artivisi.paymentgateway.entity;

/**
 * How the gateway composes a VA number inside an escrow's number space.
 *
 * <p>One value today, named rather than assumed, so a second strategy arrives as a migration and a
 * branch instead of a rewrite of every caller.
 */
public enum VaAllocationStrategy {

    /**
     * {@code vaPrefix + leftPad(slotKey, slotDigits) + pad(payerKey, payerDigits, payerPadding)}.
     *
     * <p>Deterministic on purpose: the same payer and slot get the same number on every charge,
     * because payers keep that number in their banking app and expect last term's number to work
     * this term. VA-number reuse is what makes that safe — at most one ACTIVE generation per escrow
     * and number, with every lookup generation-aware.
     *
     * <p>There is deliberately no sequential strategy. A running counter would have to be allocated
     * inside the charge-create transaction, or a rejected charge burns a number; nothing needs it
     * until a deployment collects from payers who have no standing identity.
     */
    PAYER_ENCODED
}
