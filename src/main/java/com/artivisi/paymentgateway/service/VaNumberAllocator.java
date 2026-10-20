package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.PayerPadding;
import com.artivisi.paymentgateway.entity.VaAllocationStrategy;
import com.artivisi.paymentgateway.exception.EscrowLayoutIncompleteException;
import com.artivisi.paymentgateway.exception.InvalidRequestException;
import com.artivisi.paymentgateway.exception.KeyUnencodableException;
import org.springframework.stereotype.Component;

/**
 * Composes a VA number inside one escrow's number space, from the two bank-agnostic keys the
 * consumer owns.
 *
 * <p>This replaced a validator that checked a number the consumer had composed. The split follows
 * what each side knows: a bank's prefix, total length, segment widths and padding rule are facts
 * about that bank, they already live on the escrow, and the gateway is the only component that sees
 * them. A consumer that composes the number needs a copy of all of it, which is right until the
 * layout changes or a second escrow exists — and a consumer can only ever target the escrows whose
 * layout it happens to hold, which is what stopped pay-via-any-bank from scaling.
 *
 * <p>What the consumer supplies instead:
 *
 * <ul>
 *   <li>{@code payerKey} — its own stable identifier for the payer;
 *   <li>{@code slotKey} — a short numeric key separating one payer's concurrent VAs. The consumer
 *       maps its own vocabulary (bill category, invoice type) onto slots; the gateway never learns
 *       what a slot means, which is exactly why it can allocate for any consumer.
 * </ul>
 *
 * <p>Every refusal is a 400 naming the escrow, the segment and the value, and it says whose problem
 * it is: {@link KeyUnencodableException} when the consumer's key cannot be placed, and
 * {@link EscrowLayoutIncompleteException} when the escrow cannot allocate at all. A key that
 * overflows its segment is never truncated: a truncated key composes a well-formed number that
 * belongs to a different payer, so the payment arrives, settles, and is credited to someone else.
 */
@Component
public class VaNumberAllocator {

    /** The VA number and the form a payer uses from another bank. */
    public record AllocatedNumber(String vaNumber, String interbankVaNumber) {
    }

    public AllocatedNumber allocate(EscrowAccount escrow, String payerKey, String slotKey) {
        requireLayout(escrow);
        if (payerKey == null || payerKey.isBlank()) {
            throw new InvalidRequestException("payerKey is required");
        }
        if (slotKey == null || slotKey.isBlank()) {
            throw new InvalidRequestException("slotKey is required");
        }
        String number = switch (escrow.getAllocationStrategy()) {
            case PAYER_ENCODED -> payerEncoded(escrow, payerKey, slotKey);
        };
        return new AllocatedNumber(number, escrow.getInterbankPrefix() + number);
    }

    private static String payerEncoded(EscrowAccount escrow, String payerKey, String slotKey) {
        int payerDigits = escrow.payerDigits();
        requireNumeric(escrow, "payerKey", payerKey);
        requireNumeric(escrow, "slotKey", slotKey);
        if (payerKey.length() > payerDigits) {
            throw new KeyUnencodableException("payerKey", escrow.getCode(), "payerKey '" + payerKey + "' is "
                    + payerKey.length() + " digits; escrow " + escrow.getCode() + " leaves " + payerDigits + " for it");
        }
        if (slotKey.length() > escrow.getSlotDigits()) {
            throw new KeyUnencodableException("slotKey", escrow.getCode(), "slotKey '" + slotKey + "' is "
                    + slotKey.length() + " digits; escrow " + escrow.getCode() + " leaves "
                    + escrow.getSlotDigits() + " for it");
        }
        return escrow.getVaPrefix()
                + leftPad(slotKey, escrow.getSlotDigits())
                + pad(payerKey, payerDigits, escrow.getPayerPadding());
    }

    /**
     * Refuse to allocate through an escrow whose layout is incomplete.
     *
     * <p>The layout cannot be derived from anything the gateway holds, so there is nothing to fall
     * back to. Refusing names the escrow and the missing field, which an operator can act on; the
     * alternative is a number composed from a guessed segment width, which looks valid, is accepted
     * by the bank, and collects someone else's money.
     */
    private static void requireLayout(EscrowAccount escrow) {
        VaAllocationStrategy strategy = escrow.getAllocationStrategy();
        require(escrow, "allocationStrategy", strategy);
        // Empty is a layout, not a gap: a bank whose numbers carry no prefix of their own has none.
        require(escrow, "vaPrefix", escrow.getVaPrefix());
        require(escrow, "vaDigitLength", escrow.getVaDigitLength());
        require(escrow, "slotDigits", escrow.getSlotDigits());
        require(escrow, "payerPadding", escrow.getPayerPadding());
        // Empty is a decision here, null is not: an escrow whose interbank form is the number itself
        // says so with an empty string.
        require(escrow, "interbankPrefix", escrow.getInterbankPrefix());
        if (escrow.payerDigits() < 1) {
            throw new EscrowLayoutIncompleteException(escrow.getCode(), "escrow " + escrow.getCode()
                    + " has no room for a payer key: "
                    + "prefix " + escrow.getVaPrefix().length() + " + slot " + escrow.getSlotDigits()
                    + " digits fill all " + escrow.getVaDigitLength());
        }
    }

    private static void require(EscrowAccount escrow, String field, Object value) {
        if (value == null) {
            throw new EscrowLayoutIncompleteException(escrow.getCode(), "escrow " + escrow.getCode()
                    + " cannot allocate a VA number: " + field + " is not configured");
        }
    }

    private static void requireNumeric(EscrowAccount escrow, String field, String value) {
        if (!value.chars().allMatch(Character::isDigit)) {
            throw new KeyUnencodableException(field, escrow.getCode(), field + " must be numeric for escrow "
                    + escrow.getCode() + "; got '" + value + "'");
        }
    }

    private static String pad(String value, int width, PayerPadding padding) {
        return switch (padding) {
            case LEFT -> leftPad(value, width);
            case RIGHT -> rightPad(value, width);
        };
    }

    private static String leftPad(String value, int width) {
        return value.length() >= width ? value : "0".repeat(width - value.length()) + value;
    }

    private static String rightPad(String value, int width) {
        return value.length() >= width ? value : value + "0".repeat(width - value.length());
    }
}
