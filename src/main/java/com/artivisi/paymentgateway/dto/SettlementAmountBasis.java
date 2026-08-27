package com.artivisi.paymentgateway.dto;

/**
 * What the amounts in a settlement file are measured against. The importer cannot infer this and
 * must not guess: reading a gross file as net reports every single row as an amount mismatch, and
 * reading a net file as gross does the same in the other direction. Either way the report is wrong
 * about everything and gets ignored, which is worse than refusing to run.
 */
public enum SettlementAmountBasis {

    /**
     * The amount credited to the settlement account, after the bank kept its per-transaction fee.
     * This is what an account statement carries, and it is the end-of-day settlement path: the
     * escrow's {@code settlementFee} is added back when recovering so the payer is credited what
     * they actually sent.
     */
    NET_OF_FEE,

    /**
     * The amount the payer sent, before any fee. This is what a bank's own transaction list carries
     * — BSI's portal export, for one — and comparing it against the settlement account's credits
     * would be off by the fee on every row.
     *
     * <p>A file on this basis proves the bank <em>processed</em> the payment, not that the money
     * reached the settlement account. Only a statement proves the latter.
     */
    GROSS
}
