package com.artivisi.paymentgateway.adapter.snap;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.AnnotatedElement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
    void specTraceabilityIsBidirectional() throws Exception {
        Map<String, Object> root = loadIndex();
        Set<String> indexIds = new HashSet<>();
        for (Map<String, Object> entry : (List<Map<String, Object>>) root.get("entries")) {
            indexIds.add((String) entry.get("id"));
        }

        Set<String> mainIds = new TreeSet<>();
        Set<String> testIds = new TreeSet<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        for (var candidate : scanner.findCandidateComponents("com.artivisi.paymentgateway")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            Set<String> bucket = isTestClass(type) ? testIds : mainIds;
            collect(bucket, type);
            for (var method : type.getDeclaredMethods()) {
                collect(bucket, method);
            }
            for (var field : type.getDeclaredFields()) {
                collect(bucket, field);
            }
            for (var constructor : type.getDeclaredConstructors()) {
                collect(bucket, constructor);
            }
        }

        assertThat(mainIds).as("@SnapSpec usages in production code must be discovered").isNotEmpty();

        Set<String> all = new TreeSet<>(mainIds);
        all.addAll(testIds);
        assertThat(indexIds).as("every @SnapSpec id must exist in the index").containsAll(all);
        assertThat(testIds).as("every implemented @SnapSpec id must be verified by a test").containsAll(mainIds);
    }

    private static boolean isTestClass(Class<?> type) {
        var codeSource = type.getProtectionDomain().getCodeSource();
        return codeSource != null && codeSource.getLocation().getPath().contains("test-classes");
    }

    private static void collect(Collection<String> ids, AnnotatedElement element) {
        SnapSpec annotation = element.getAnnotation(SnapSpec.class);
        if (annotation != null) {
            ids.addAll(List.of(annotation.value()));
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
