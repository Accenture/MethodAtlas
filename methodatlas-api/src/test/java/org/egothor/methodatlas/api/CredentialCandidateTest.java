package org.egothor.methodatlas.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the credential-detection value types: {@link CredentialCandidate},
 * {@link CredentialScanUnit}, and {@link CredentialDetectorConfig}.
 *
 * <p>
 * Verifies that field accessors return the values passed to the constructor,
 * that the compact constructors reject {@code null} on their required reference
 * fields, and that {@link CredentialDetectorConfig} defensively deep-copies its
 * {@code properties} map (unmodifiable outer map of unmodifiable inner lists).
 * </p>
 */
@Tag("unit")
@Tag("api")
class CredentialCandidateTest {

    // -------------------------------------------------------------------------
    // CredentialCandidate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CredentialCandidate stores all fields and accessors return them verbatim")
    @Tag("positive")
    void carriesAllFields() {
        CredentialCandidate c = new CredentialCandidate("builtin", "aws-access-key-id",
                CredentialCategory.PROVIDER_TOKEN, 12, 8, 12, 28, "AKIAIOSFODNN7EXAMPLE");
        assertEquals("builtin", c.detectorId());
        assertEquals("aws-access-key-id", c.ruleId());
        assertEquals(CredentialCategory.PROVIDER_TOKEN, c.category());
        assertEquals(12, c.beginLine());
        assertEquals(8, c.beginColumn());
        assertEquals(12, c.endLine());
        assertEquals(28, c.endColumn());
        assertEquals("AKIAIOSFODNN7EXAMPLE", c.matchedValue());
    }

    @Test
    @DisplayName("CredentialCandidate rejects null detectorId")
    @Tag("negative")
    void candidateRejectsNullDetectorId() {
        assertThrows(NullPointerException.class, () -> new CredentialCandidate(
                null, "rule", CredentialCategory.OTHER, 1, 1, 1, 2, "v"));
    }

    @Test
    @DisplayName("CredentialCandidate rejects null ruleId")
    @Tag("negative")
    void candidateRejectsNullRuleId() {
        assertThrows(NullPointerException.class, () -> new CredentialCandidate(
                "builtin", null, CredentialCategory.OTHER, 1, 1, 1, 2, "v"));
    }

    @Test
    @DisplayName("CredentialCandidate rejects null category")
    @Tag("negative")
    void candidateRejectsNullCategory() {
        assertThrows(NullPointerException.class, () -> new CredentialCandidate(
                "builtin", "rule", null, 1, 1, 1, 2, "v"));
    }

    @Test
    @DisplayName("CredentialCandidate rejects null matchedValue")
    @Tag("negative")
    void candidateRejectsNullMatchedValue() {
        assertThrows(NullPointerException.class, () -> new CredentialCandidate(
                "builtin", "rule", CredentialCategory.OTHER, 1, 1, 1, 2, null));
    }

    // -------------------------------------------------------------------------
    // CredentialScanUnit
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CredentialScanUnit stores all fields and accessors return them verbatim")
    @Tag("positive")
    void scanUnitCarriesAllFields() {
        Path path = Path.of("Foo.java");
        CredentialScanUnit unit = new CredentialScanUnit(path, "com.acme.Foo", "class Foo {}", "java");
        assertEquals(path, unit.filePath());
        assertEquals("com.acme.Foo", unit.fqcn());
        assertEquals("class Foo {}", unit.source());
        assertEquals("java", unit.languageId());
    }

    @Test
    @DisplayName("CredentialScanUnit allows a null fqcn (non-test file under a wider mask)")
    @Tag("positive")
    void scanUnitAllowsNullFqcn() {
        CredentialScanUnit unit = new CredentialScanUnit(Path.of("Prod.java"), null, "x", "java");
        assertEquals(null, unit.fqcn());
    }

    @Test
    @DisplayName("CredentialScanUnit rejects null filePath")
    @Tag("negative")
    void scanUnitRejectsNullPath() {
        assertThrows(NullPointerException.class,
                () -> new CredentialScanUnit(null, "Foo", "src", "java"));
    }

    @Test
    @DisplayName("CredentialScanUnit rejects null source")
    @Tag("negative")
    void scanUnitRejectsNullSource() {
        assertThrows(NullPointerException.class,
                () -> new CredentialScanUnit(Path.of("Foo.java"), "Foo", null, "java"));
    }

    // -------------------------------------------------------------------------
    // CredentialDetectorConfig
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CredentialDetectorConfig deep-copies properties (inner lists unmodifiable and detached)")
    @Tag("positive")
    void detectorConfigDeepCopiesProperties() {
        Map<String, List<String>> props = new HashMap<>();
        props.put("k", new ArrayList<>(List.of("v")));
        CredentialDetectorConfig cfg = new CredentialDetectorConfig(4.0, Optional.empty(), props);
        props.get("k").add("mutated");
        assertEquals(1, cfg.properties().get("k").size(), "config must defensively copy");
        assertThrows(UnsupportedOperationException.class,
                () -> cfg.properties().put("x", List.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> cfg.properties().get("k").add("y"));
    }

    @Test
    @DisplayName("CredentialDetectorConfig rejects null customCatalog")
    @Tag("negative")
    void detectorConfigRejectsNullCatalog() {
        assertThrows(NullPointerException.class,
                () -> new CredentialDetectorConfig(4.0, null, Map.of()));
    }

    @Test
    @DisplayName("CredentialDetectorConfig rejects null properties")
    @Tag("negative")
    void detectorConfigRejectsNullProperties() {
        assertThrows(NullPointerException.class,
                () -> new CredentialDetectorConfig(4.0, Optional.empty(), null));
    }
}
