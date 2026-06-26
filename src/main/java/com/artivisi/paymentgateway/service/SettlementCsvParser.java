package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.dto.SettlementCredit;
import com.artivisi.paymentgateway.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an imported settlement statement into {@link SettlementCredit}s. Format (header row +
 * data): {@code vaNumber,bankReference,amount,transactionTime}, transactionTime ISO-8601 instant.
 * Fails loud on malformed rows.
 */
@Component
public class SettlementCsvParser {

    public List<SettlementCredit> parse(InputStream input) {
        List<SettlementCredit> credits = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (header) {
                    header = false;
                    continue;
                }
                String[] fields = line.split(",", -1);
                if (fields.length < 4) {
                    throw new InvalidRequestException(
                            "settlement row " + lineNumber + " must have 4 columns: " + line);
                }
                try {
                    credits.add(new SettlementCredit(fields[0].trim(), fields[1].trim(),
                            new BigDecimal(fields[2].trim()), Instant.parse(fields[3].trim())));
                } catch (RuntimeException e) {
                    throw new InvalidRequestException(
                            "settlement row " + lineNumber + " is invalid: " + line + " (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read settlement statement", e);
        }
        return credits;
    }
}
