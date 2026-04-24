package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link AiResultCache}.
 */
@Tag("unit")
@Tag("ai-cache")
class AiResultCacheTest {

    // -------------------------------------------------------------------------
    // empty()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("empty cache is not active and always returns misses")
    @Tag("positive")
    void empty_isNotActiveAndAlwaysMisses() {
        AiResultCache cache = AiResultCache.empty();

        assertFalse(cache.isActive());
        assertTrue(cache.lookup("any-hash").isEmpty());
        assertEquals(1, cache.misses());
        assertEquals(0, cache.hits());
    }

    // -------------------------------------------------------------------------
    // load() — no content_hash column
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CSV without content_hash column produces an inactive cache (all misses)")
    @Tag("edge-case")
    void load_csvWithoutContentHashColumn_producesInactiveCache(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("no-hash.csv");
        Files.writeString(csv,
                "fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score\n"
                        + "com.acme.FooTest,testFoo,5,,true,SECURITY: foo,security,Covers foo.,0.0\n",
                StandardCharsets.UTF_8);

        AiResultCache cache = AiResultCache.load(csv);

        assertFalse(cache.isActive(), "Cache without content_hash column should not be active");
        assertTrue(cache.lookup("anything").isEmpty());
    }

    // -------------------------------------------------------------------------
    // load() — no AI columns
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CSV without AI columns (no -ai flag on producing run) produces an inactive cache")
    @Tag("edge-case")
    void load_csvWithoutAiColumns_producesInactiveCache(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("no-ai.csv");
        Files.writeString(csv,
                "fqcn,method,loc,tags,content_hash\n"
                        + "com.acme.FooTest,testFoo,5,,abc123\n",
                StandardCharsets.UTF_8);

        AiResultCache cache = AiResultCache.load(csv);

        assertFalse(cache.isActive(), "Cache without AI columns should not be active");
        assertTrue(cache.lookup("abc123").isEmpty());
    }

    // -------------------------------------------------------------------------
    // load() — happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CSV with content_hash and AI columns produces a hit for matching hash")
    @Tag("positive")
    void load_csvWithContentHashAndAiColumns_hitOnMatchingHash(@TempDir Path tempDir) throws Exception {
        String hash = "a".repeat(64);
        Path csv = buildMinimalCsv(tempDir, hash,
                "com.acme.FooTest", "testFoo", "true", "SECURITY: foo", "security", "Covers foo.", "0.0");

        AiResultCache cache = AiResultCache.load(csv);

        assertTrue(cache.isActive());
        Optional<AiClassSuggestion> result = cache.lookup(hash);
        assertTrue(result.isPresent(), "Expected a hit for the matching hash");
        assertEquals(1, result.get().methods().size());
        assertEquals("testFoo", result.get().methods().get(0).methodName());
        assertTrue(result.get().methods().get(0).securityRelevant());
        assertEquals("SECURITY: foo", result.get().methods().get(0).displayName());
        assertEquals(1, cache.hits());
        assertEquals(0, cache.misses());
    }

    @Test
    @DisplayName("method suggestion fields are restored correctly from CSV")
    @Tag("positive")
    void load_methodSuggestionFieldsRestoredCorrectly(@TempDir Path tempDir) throws Exception {
        String hash = "b".repeat(64);
        Path csv = buildMinimalCsv(tempDir, hash,
                "com.acme.AuthTest", "testLogin", "true", "SECURITY: auth", "security;auth", "Tests login.", "0.7");

        AiResultCache cache = AiResultCache.load(csv);
        var suggestion = cache.lookup(hash).orElseThrow().methods().get(0);

        assertEquals("testLogin", suggestion.methodName());
        assertTrue(suggestion.securityRelevant());
        assertEquals("SECURITY: auth", suggestion.displayName());
        assertEquals(2, suggestion.tags().size());
        assertTrue(suggestion.tags().contains("security"));
        assertTrue(suggestion.tags().contains("auth"));
        assertEquals("Tests login.", suggestion.reason());
        assertEquals(0.7, suggestion.interactionScore(), 0.001);
    }

    @Test
    @DisplayName("non-security method (ai_security_relevant=false) is cached and restores as non-security")
    @Tag("positive")
    void load_nonSecurityMethod_cachedAndRestoredAsNonSecurity(@TempDir Path tempDir) throws Exception {
        String hash = "c".repeat(64);
        Path csv = buildMinimalCsv(tempDir, hash,
                "com.acme.FooTest", "testCount", "false", "", "", "Not security-relevant.", "0.0");

        AiResultCache cache = AiResultCache.load(csv);

        assertTrue(cache.isActive());
        var suggestion = cache.lookup(hash).orElseThrow().methods().get(0);
        assertFalse(suggestion.securityRelevant());
        assertEquals("testCount", suggestion.methodName());
    }

    @Test
    @DisplayName("multiple methods sharing the same content_hash are grouped into one AiClassSuggestion")
    @Tag("positive")
    void load_multipleMethodsSameHash_groupedIntoOneSuggestion(@TempDir Path tempDir) throws Exception {
        String hash = "d".repeat(64);
        Path csv = tempDir.resolve("multi.csv");
        Files.writeString(csv,
                "fqcn,method,loc,tags,content_hash,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score\n"
                        + "com.acme.FooTest,testA,3,,"+hash+",true,SECURITY: a,security,Reason A.,0.0\n"
                        + "com.acme.FooTest,testB,5,,"+hash+",false,,,Not relevant.,0.0\n",
                StandardCharsets.UTF_8);

        AiResultCache cache = AiResultCache.load(csv);

        Optional<AiClassSuggestion> result = cache.lookup(hash);
        assertTrue(result.isPresent());
        assertEquals(2, result.get().methods().size());
    }

    // -------------------------------------------------------------------------
    // lookup() — miss
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("lookup returns empty for a hash not present in the cache")
    @Tag("edge-case")
    void lookup_unknownHash_returnsMiss(@TempDir Path tempDir) throws Exception {
        String hash = "e".repeat(64);
        Path csv = buildMinimalCsv(tempDir, hash,
                "com.acme.FooTest", "testFoo", "true", "SECURITY: foo", "security", "Reason.", "0.0");

        AiResultCache cache = AiResultCache.load(csv);
        Optional<AiClassSuggestion> result = cache.lookup("f".repeat(64));

        assertTrue(result.isEmpty(), "Different hash should be a miss");
        assertEquals(1, cache.misses());
        assertEquals(0, cache.hits());
    }

    @Test
    @DisplayName("lookup with null hash returns empty and increments miss counter")
    @Tag("edge-case")
    void lookup_nullHash_returnsMissAndIncrementsMisses(@TempDir Path tempDir) throws Exception {
        String hash = "f".repeat(64);
        Path csv = buildMinimalCsv(tempDir, hash,
                "com.acme.FooTest", "testFoo", "true", "SECURITY: foo", "security", "Reason.", "0.0");

        AiResultCache cache = AiResultCache.load(csv);
        assertTrue(cache.lookup(null).isEmpty());
        assertEquals(1, cache.misses());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Path buildMinimalCsv(Path dir, String hash,
            String fqcn, String method, String aiSecurityRelevant,
            String aiDisplayName, String aiTags, String aiReason, String aiInteractionScore)
            throws Exception {
        Path csv = dir.resolve("cache.csv");
        Files.writeString(csv,
                "fqcn,method,loc,tags,content_hash,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score\n"
                        + fqcn + "," + method + ",5,," + hash + ","
                        + aiSecurityRelevant + "," + aiDisplayName + "," + aiTags + ","
                        + aiReason + "," + aiInteractionScore + "\n",
                StandardCharsets.UTF_8);
        return csv;
    }
}
