package com.artivisi.paymentgateway.adapter.snap;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the SNAP reference index ({@code docs/snap/snap-1.0.2.json}) that {@link SnapSpec}
 * tags point at: stable unique ids, required fields, and source-document checksums present.
 */
class SnapSpecIndexTest {

    private static final Path INDEX = Path.of("docs/snap/snap-1.0.2.json");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadIndex() throws Exception {
        return JsonMapper.builder().build().readValue(Files.readString(INDEX), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void entriesHaveUniqueIdsAndRequiredFields() throws Exception {
        Map<String, Object> root = loadIndex();
        List<Map<String, Object>> entries = (List<Map<String, Object>>) root.get("entries");
        assertThat(entries).isNotEmpty();

        Set<String> ids = new HashSet<>();
        for (Map<String, Object> entry : entries) {
            String id = (String) entry.get("id");
            assertThat(id).as("entry id").isNotBlank();
            assertThat(id).as("id namespace").startsWith("snap.");
            assertThat((String) entry.get("source")).as("source of " + id).isNotBlank();
            assertThat((String) entry.get("summary")).as("summary of " + id).isNotBlank();
            assertThat(ids.add(id)).as("duplicate id " + id).isTrue();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sourceDocumentsAreVerifiable() throws Exception {
        Map<String, Object> meta = (Map<String, Object>) loadIndex().get("meta");
        List<Map<String, Object>> docs = (List<Map<String, Object>>) meta.get("sourceDocuments");
        assertThat(docs).hasSize(2);
        for (Map<String, Object> doc : docs) {
            assertThat((String) doc.get("sha256")).as("sha256").hasSize(64);
            assertThat(doc.get("version")).isEqualTo("1.0.2");
            assertThat((String) doc.get("releaseDate")).isNotBlank();
            assertThat((String) doc.get("sourceUrl")).startsWith("https://apidevportal.aspi-indonesia.or.id/");
        }
    }
}
