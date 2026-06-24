package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

/**
 * Validates a consumer-supplied VA number against the escrow's number space
 * (prefix + digit length, numeric). The gateway never generates numbers.
 */
@Component
public class NumberSpaceValidator {

    public void validate(EscrowAccount escrow, String vaNumber) {
        if (vaNumber == null || vaNumber.isBlank()) {
            throw new InvalidRequestException("vaNumber is required");
        }
        if (!vaNumber.chars().allMatch(Character::isDigit)) {
            throw new InvalidRequestException("vaNumber must be numeric: " + vaNumber);
        }
        Integer length = escrow.getVaDigitLength();
        if (length != null && vaNumber.length() != length) {
            throw new InvalidRequestException(
                    "vaNumber must be exactly " + length + " digits for escrow " + escrow.getCode()
                            + "; got " + vaNumber.length());
        }
        String prefix = escrow.getVaPrefix();
        if (prefix != null && !prefix.isBlank() && !vaNumber.startsWith(prefix)) {
            throw new InvalidRequestException(
                    "vaNumber must start with prefix " + prefix + " for escrow " + escrow.getCode());
        }
    }
}
