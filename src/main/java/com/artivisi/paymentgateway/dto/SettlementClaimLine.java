package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.DiscrepancyType;

import java.math.BigDecimal;

/**
 * One summary line of a settlement claim: a discrepancy type, how many of them, what they are worth,
 * and what the bank is being told it means.
 *
 * <p>The explanation is the point. A list of enum names is a data dump the recipient has to decode;
 * a claim has to say what each class of row is asking the bank to do, or it gets bounced back for
 * clarification before anyone looks at the money.
 */
public record SettlementClaimLine(
        DiscrepancyType type,
        String meaning,
        long count,
        BigDecimal total
) {
    /** What this discrepancy means for the bank, in the words a claim needs. */
    public static String meaningOf(DiscrepancyType type) {
        return switch (type) {
            case NOTIFIED_NOT_SETTLED ->
                    "We received a payment notification and recorded the payment, but no matching credit "
                            + "appears in the settlement. Please confirm whether the funds were settled.";
            case PAID_NOT_NOTIFIED_RECOVERED ->
                    "The settlement contains a credit we never received a notification for. The payment "
                            + "has been recovered on our side; please check why the notification was not delivered.";
            case AMOUNT_MISMATCH ->
                    "The settled amount differs from the payment we recorded, beyond the agreed "
                            + "per-transaction fee. Please confirm the amount credited.";
            case UNMATCHED_CREDIT ->
                    "The settlement contains a credit for a virtual account number that does not belong "
                            + "to this escrow. Please confirm the destination of these funds.";
            case DUPLICATE ->
                    "The settlement lists the same bank reference more than once. Please confirm whether "
                            + "this is one transaction or several.";
            case RECOVERY_FAILED ->
                    "A settled credit could not be applied on our side and needs joint review.";
        };
    }
}
