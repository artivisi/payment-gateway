package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.BankCallback;
import com.artivisi.paymentgateway.exception.InvalidRequestException;
import com.artivisi.paymentgateway.repository.BankCallbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps what the bank actually sent, and says so when it sends something we do not model.
 *
 * <p>A typed DTO discards unknown properties without a word. BSI sent {@code nomorJurnalPembukuan} on
 * every payment notification from launch until 2026-08-25 and nothing in this system saw it; it came
 * to light only because the legacy service being retired logged raw request text. This exists so the
 * next such field is visible the first time it arrives.
 */
@Service
public class BankCallbackRecorder {

    private static final Logger log = LoggerFactory.getLogger(BankCallbackRecorder.class);

    /**
     * Never stored. The checksum is derived from the escrow's shared key, and a table of raw bank
     * messages is the easiest place to break "never log secrets or signatures" by accident.
     */
    private static final String SIGNATURE_FIELD = "checksum";

    private static final String REDACTED = "<redacted>";

    private final BankCallbackRepository repository;
    private final ObjectMapper objectMapper;

    public BankCallbackRecorder(BankCallbackRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Record the message and return it as {@code type}.
     *
     * <p>Runs in its own transaction so the record survives whatever the caller does next: a payment
     * that is rejected, or one that fails halfway, is exactly the message someone will want to read
     * afterwards. Recording must never be the reason a callback fails, so a failure here is logged
     * and swallowed — losing the copy is bad, refusing the bank's money because we could not file it
     * is worse.
     */
    public <T> T record(String provider, String rawBody, Class<T> type) {
        JsonNode tree;
        try {
            tree = objectMapper.readTree(rawBody);
        } catch (RuntimeException e) {
            throw new InvalidRequestException("unparseable " + provider + " callback: " + e.getMessage());
        }
        try {
            save(provider, tree, unknownFields(tree, type));
        } catch (RuntimeException e) {
            log.error("Could not record the {} callback; processing it anyway", provider, e);
        }
        return objectMapper.treeToValue(tree, type);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void save(String provider, JsonNode tree, List<String> unknown) {
        if (!unknown.isEmpty()) {
            // Loud, but not fatal. Refusing a real payment because the bank added a field would be a
            // far worse failure than not modelling it yet.
            log.warn("{} sent field(s) this gateway does not model: {} — recorded in bank_callback",
                    provider, String.join(", ", unknown));
        }
        ObjectNode redacted = (ObjectNode) tree.deepCopy();
        if (redacted.has(SIGNATURE_FIELD)) {
            redacted.put(SIGNATURE_FIELD, REDACTED);
        }
        BankCallback row = new BankCallback();
        row.setProvider(provider);
        row.setReceivedAt(Instant.now());
        row.setAction(text(tree, "action"));
        row.setVaNumber(text(tree, "nomorPembayaran"));
        row.setBankReference(text(tree, "idTransaksi"));
        row.setPayload(redacted.toString());
        row.setUnknownFields(unknown.isEmpty() ? null : String.join(",", unknown));
        repository.save(row);
    }

    /** Property names present in the message that {@code type} has no component for. */
    private static List<String> unknownFields(JsonNode tree, Class<?> type) {
        Set<String> known = type.isRecord()
                ? Arrays.stream(type.getRecordComponents()).map(c -> c.getName()).collect(Collectors.toSet())
                : Set.of();
        List<String> unknown = new ArrayList<>();
        for (java.util.Map.Entry<String, JsonNode> property : tree.properties()) {
            if (!known.contains(property.getKey())) {
                unknown.add(property.getKey());
            }
        }
        return unknown;
    }

    private static String text(JsonNode tree, String field) {
        JsonNode n = tree.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }
}
