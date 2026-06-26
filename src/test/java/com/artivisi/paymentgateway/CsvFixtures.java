package com.artivisi.paymentgateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads classpath CSV fixtures under {@code /testdata} into header-keyed rows (and raw bytes). */
public final class CsvFixtures {

    private CsvFixtures() {
    }

    public static List<Map<String, String>> rows(String resource) {
        List<Map<String, String>> rows = new ArrayList<>();
        try (InputStream in = open(resource);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().filter(line -> !line.isBlank()).toList();
            if (lines.isEmpty()) {
                return rows;
            }
            String[] headers = lines.getFirst().split(",", -1);
            for (int i = 1; i < lines.size(); i++) {
                String[] values = lines.get(i).split(",", -1);
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < headers.length; c++) {
                    row.put(headers[c].trim(), c < values.length ? values[c].trim() : "");
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read fixture " + resource, e);
        }
    }

    public static byte[] bytes(String resource) {
        try (InputStream in = open(resource)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read fixture " + resource, e);
        }
    }

    private static InputStream open(String resource) {
        InputStream in = CsvFixtures.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("fixture not found on classpath: " + resource);
        }
        return in;
    }
}
