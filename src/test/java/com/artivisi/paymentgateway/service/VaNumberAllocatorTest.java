package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.PayerPadding;
import com.artivisi.paymentgateway.entity.VaAllocationStrategy;
import com.artivisi.paymentgateway.exception.EscrowLayoutIncompleteException;
import com.artivisi.paymentgateway.exception.InvalidRequestException;
import com.artivisi.paymentgateway.exception.KeyUnencodableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The composition rule itself, without a database. Everything here is arithmetic on an escrow's
 * layout, and a wrong digit means a payment credited to someone else — so each rule gets a case
 * rather than being covered incidentally through a charge.
 */
class VaNumberAllocatorTest {

    private final VaNumberAllocator allocator = new VaNumberAllocator();

    private static EscrowAccount escrow(String prefix, int digits, int slotDigits, PayerPadding padding,
                                        String interbankPrefix) {
        EscrowAccount e = new EscrowAccount();
        e.setCode("test-escrow");
        e.setVaPrefix(prefix);
        e.setVaDigitLength(digits);
        e.setAllocationStrategy(VaAllocationStrategy.PAYER_ENCODED);
        e.setSlotDigits(slotDigits);
        e.setPayerPadding(padding);
        e.setInterbankPrefix(interbankPrefix);
        return e;
    }

    @Test
    void rightPadding_reproducesNumbersAlreadyIssuedUnderThatRule() {
        // The layout one live deployment has been issuing under for years: 3-digit prefix, a 2-digit
        // slot carrying the bill category, and the debtor code right-filled across the remaining 11.
        EscrowAccount e = escrow("920", 16, 2, PayerPadding.RIGHT, "");

        var allocated = allocator.allocate(e, "2699000001", "1");

        assertThat(allocated.vaNumber()).isEqualTo("9200126990000010");
        assertThat(allocated.vaNumber()).hasSize(16);
    }

    @Test
    void anEmptyPrefix_isALayout_notAGap() {
        // A bank whose numbers carry no prefix of their own: two digits of slot, ten of payer, and
        // nothing in front. Treating the empty prefix as unconfigured refused every charge through
        // such an escrow.
        EscrowAccount e = escrow("", 12, 2, PayerPadding.RIGHT, "");

        var allocated = allocator.allocate(e, "20261001", "7");

        assertThat(allocated.vaNumber()).isEqualTo("072026100100");
        assertThat(allocated.vaNumber()).hasSize(12);
    }

    /**
     * Carried over from the consumer-side composer this replaced, where it was a real regression: a
     * short payer key fills the leading positions of its segment, so debtor {@code 18200417} is
     * {@code 16 1820041700} — the number the bank already carried — and left-filling minted
     * {@code 16 0018200417}, a VA nobody had ever seen, against which a real payment could not match.
     */
    @Test
    void aShortPayerKey_fillsTheLeadingPositions_underRightPadding() {
        EscrowAccount e = escrow("", 12, 2, PayerPadding.RIGHT, "");

        assertThat(allocator.allocate(e, "18200417", "16").vaNumber()).isEqualTo("161820041700");
    }

    @Test
    void aNullPrefix_isStillRefused() {
        EscrowAccount e = escrow(null, 12, 2, PayerPadding.RIGHT, "");

        assertThatThrownBy(() -> allocator.allocate(e, "20261001", "7"))
                .isInstanceOf(EscrowLayoutIncompleteException.class)
                .hasMessageContaining("vaPrefix is not configured");
    }

    @Test
    void leftPadding_fillsTheOtherEnd() {
        EscrowAccount e = escrow("920", 16, 2, PayerPadding.LEFT, "");

        assertThat(allocator.allocate(e, "2699000001", "1").vaNumber()).isEqualTo("9200102699000001");
    }

    @Test
    void theSlotIsAlwaysLeftFilled_soCategoryOneAndCategoryTenDoNotCollide() {
        EscrowAccount e = escrow("900", 10, 2, PayerPadding.RIGHT, "");

        assertThat(allocator.allocate(e, "12345", "1").vaNumber()).isEqualTo("9000112345");
        assertThat(allocator.allocate(e, "12345", "10").vaNumber()).isEqualTo("9001012345");
    }

    @Test
    void theSameKeysAlwaysGiveTheSameNumber() {
        EscrowAccount e = escrow("900", 10, 1, PayerPadding.RIGHT, "");

        // Payers keep the number in their banking app, so last term's number has to work this term.
        assertThat(allocator.allocate(e, "000123", "0").vaNumber())
                .isEqualTo(allocator.allocate(e, "000123", "0").vaNumber());
    }

    @Test
    void theInterbankFormIsComposedHere_notByTheConsumer() {
        EscrowAccount e = escrow("920", 16, 2, PayerPadding.RIGHT, "9200900");

        var allocated = allocator.allocate(e, "2699000001", "1");

        assertThat(allocated.interbankVaNumber()).isEqualTo("92009009200126990000010");
    }

    @Test
    void anEmptyInterbankPrefixMeansTheNumberItself() {
        EscrowAccount e = escrow("900", 10, 1, PayerPadding.RIGHT, "");

        var allocated = allocator.allocate(e, "000123", "0");

        assertThat(allocated.interbankVaNumber()).isEqualTo(allocated.vaNumber());
    }

    @Test
    void aPayerKeyThatOverflowsItsSegment_isRefusedRatherThanTruncated() {
        EscrowAccount e = escrow("900", 10, 1, PayerPadding.RIGHT, "");

        // Truncation would compose a valid-looking number that belongs to a different payer: the
        // bank would accept the payment, settle it, and credit the wrong student.
        assertThatThrownBy(() -> allocator.allocate(e, "1234567", "0"))
                .isInstanceOfSatisfying(KeyUnencodableException.class,
                        k -> assertThat(k.getField()).isEqualTo("payerKey"))
                .hasMessageContaining("7 digits")
                .hasMessageContaining("leaves 6");
    }

    @Test
    void aSlotKeyThatOverflowsItsSegment_isRefused() {
        EscrowAccount e = escrow("900", 10, 1, PayerPadding.RIGHT, "");

        assertThatThrownBy(() -> allocator.allocate(e, "000123", "12"))
                .isInstanceOfSatisfying(KeyUnencodableException.class,
                        k -> assertThat(k.getField()).isEqualTo("slotKey"))
                .hasMessageContaining("slotKey");
    }

    @Test
    void aNonNumericKey_isRefused() {
        EscrowAccount e = escrow("900", 10, 1, PayerPadding.RIGHT, "");

        assertThatThrownBy(() -> allocator.allocate(e, "00A123", "0"))
                .isInstanceOf(KeyUnencodableException.class)
                .hasMessageContaining("payerKey must be numeric");
        assertThatThrownBy(() -> allocator.allocate(e, "000123", "x"))
                .isInstanceOf(KeyUnencodableException.class)
                .hasMessageContaining("slotKey must be numeric");
    }

    @Test
    void aMissingKey_isRefused() {
        EscrowAccount e = escrow("900", 10, 1, PayerPadding.RIGHT, "");

        assertThatThrownBy(() -> allocator.allocate(e, null, "0"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("payerKey is required");
        assertThatThrownBy(() -> allocator.allocate(e, "000123", "  "))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("slotKey is required");
    }

    @Test
    void anIncompleteLayout_isRefusedByName() {
        // How an escrow that predates gateway-side allocation arrives: the migration could state the
        // strategy and nothing else, because nothing in the database implies the segment widths.
        EscrowAccount e = escrow("900", 10, 1, PayerPadding.RIGHT, "");
        e.setPayerPadding(null);

        assertThatThrownBy(() -> allocator.allocate(e, "000123", "0"))
                .isInstanceOf(EscrowLayoutIncompleteException.class)
                .hasMessageContaining("test-escrow")
                .hasMessageContaining("payerPadding is not configured");
    }

    @Test
    void aNullInterbankPrefix_isRefused_becauseEmptyIsTheWayToSayTheNumberItself() {
        EscrowAccount e = escrow("900", 10, 1, PayerPadding.RIGHT, null);

        assertThatThrownBy(() -> allocator.allocate(e, "000123", "0"))
                .isInstanceOf(EscrowLayoutIncompleteException.class)
                .hasMessageContaining("interbankPrefix is not configured");
    }

    @Test
    void aLayoutWithNoRoomForAPayerKey_isRefused() {
        EscrowAccount e = escrow("900", 10, 7, PayerPadding.RIGHT, "");

        assertThatThrownBy(() -> allocator.allocate(e, "1", "0"))
                .isInstanceOf(EscrowLayoutIncompleteException.class)
                .hasMessageContaining("no room for a payer key");
    }
}
