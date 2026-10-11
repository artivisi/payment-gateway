package com.artivisi.paymentgateway.entity;

/**
 * Which end of the payer segment is zero-filled when the payer key is shorter than the segment.
 *
 * <p>Explicit per escrow, with no default, because both rules are in live use and the wrong one
 * mints a number that belongs to a different payer. {@code RIGHT} exists to reproduce numbers
 * already issued under that rule: a deployment that has been issuing right-filled numbers for years
 * cannot switch, since every payer's saved number would stop working.
 */
public enum PayerPadding {

    /** Zeros before the key: payer {@code 2699000001} in 11 digits becomes {@code 02699000001}. */
    LEFT,

    /** Zeros after the key: payer {@code 2699000001} in 11 digits becomes {@code 26990000010}. */
    RIGHT
}
