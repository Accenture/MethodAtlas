package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ClassificationOverride}.
 */
@Tag("unit")
@Tag("override")
class ClassificationOverrideTest {

    // -------------------------------------------------------------------------
    // Empty override (no-op)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("empty() returns suggestion unchanged when no overrides are registered")
    @Tag("positive")
    void empty_returnsOriginalSuggestion() {
        AiClassSuggestion original = classSuggestion("com.acme.FooTest",
                List.of(methodSuggestion("test_one", true, List.of("auth"), "reason-a", 0.9)));

        AiClassSuggestion result = ClassificationOverride.empty()
                .apply("com.acme.FooTest", original, List.of("test_one"));

        assertEquals(original, result);
    }

    @Test
    @DisplayName("empty() returns null when suggestion is null and no overrides exist")
    @Tag("positive")
    void empty_returnsNullWhenSuggestionIsNull() {
        assertNull(ClassificationOverride.empty()
                .apply("com.acme.FooTest", null, List.of("test_one")));
    }

    @Test
    @DisplayName("hasOverridesFor returns false on the empty instance")
    @Tag("positive")
    void empty_hasOverridesFor_returnsFalse() {
        assertFalse(ClassificationOverride.empty().hasOverridesFor("com.acme.FooTest"));
    }

    // -------------------------------------------------------------------------
    // Method-level override
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("method-level override replaces securityRelevant and sets confidence=1.0")
    @Tag("positive")
    void methodLevel_overridesSecurityRelevant(@TempDir Path tmp) throws IOException {
        Path file = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.FooTest
                    method: test_one
                    securityRelevant: true
                    tags: [auth, session]
                    displayName: "SECURITY: auth check"
                    reason: "human rationale"
                """);

        ClassificationOverride co = ClassificationOverride.load(file);
        AiClassSuggestion result = co.apply("com.acme.FooTest",
                classSuggestion("com.acme.FooTest",
                        List.of(methodSuggestion("test_one", false, List.of(), null, 0.1))),
                List.of("test_one"));

        assertNotNull(result);
        AiMethodSuggestion m = result.methods().get(0);
        assertTrue(m.securityRelevant());
        assertEquals(List.of("auth", "session"), m.tags());
        assertEquals("SECURITY: auth check", m.displayName());
        assertEquals("human rationale", m.reason());
        assertEquals(1.0, m.confidence(), 1e-9);
    }

    @Test
    @DisplayName("method-level override setting securityRelevant=false sets confidence=0.0")
    @Tag("positive")
    void methodLevel_falseSecurityRelevant_setsConfidenceZero(@TempDir Path tmp) throws IOException {
        Path file = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.FooTest
                    method: test_one
                    securityRelevant: false
                    reason: "not security-relevant"
                """);

        ClassificationOverride co = ClassificationOverride.load(file);
        AiClassSuggestion result = co.apply("com.acme.FooTest",
                classSuggestion("com.acme.FooTest",
                        List.of(methodSuggestion("test_one", true, List.of("auth"), null, 0.95))),
                List.of("test_one"));

        assertNotNull(result);
        AiMethodSuggestion m = result.methods().get(0);
        assertFalse(m.securityRelevant());
        assertEquals(0.0, m.confidence(), 1e-9);
        assertEquals("not security-relevant", m.reason());
        // tags not specified in override → keep AI tags
        assertEquals(List.of("auth"), m.tags());
    }

    // -------------------------------------------------------------------------
    // Class-level override
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("class-level override applies to all methods in the class")
    @Tag("positive")
    void classLevel_appliesToAllMethods(@TempDir Path tmp) throws IOException {
        Path file = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.AuthTest
                    securityRelevant: true
                    tags: [auth]
                """);

        ClassificationOverride co = ClassificationOverride.load(file);
        AiClassSuggestion result = co.apply("com.acme.AuthTest",
                classSuggestion("com.acme.AuthTest", List.of(
                        methodSuggestion("test_login", false, List.of(), null, 0.1),
                        methodSuggestion("test_logout", false, List.of(), null, 0.2))),
                List.of("test_login", "test_logout"));

        assertNotNull(result);
        for (AiMethodSuggestion m : result.methods()) {
            assertTrue(m.securityRelevant(), "expected securityRelevant=true for " + m.methodName());
            assertEquals(List.of("auth"), m.tags());
            assertEquals(1.0, m.confidence(), 1e-9);
        }
    }

    // -------------------------------------------------------------------------
    // Method-level beats class-level
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("method-level override takes precedence over class-level for the targeted method")
    @Tag("positive")
    void methodLevel_precedenceOverClassLevel(@TempDir Path tmp) throws IOException {
        Path file = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.MixedTest
                    securityRelevant: true
                    tags: [class-tag]
                  - fqcn: com.acme.MixedTest
                    method: test_utility
                    securityRelevant: false
                    reason: "utility only"
                """);

        ClassificationOverride co = ClassificationOverride.load(file);
        AiClassSuggestion result = co.apply("com.acme.MixedTest",
                classSuggestion("com.acme.MixedTest", List.of(
                        methodSuggestion("test_auth", false, List.of(), null, 0.0),
                        methodSuggestion("test_utility", false, List.of(), null, 0.0))),
                List.of("test_auth", "test_utility"));

        assertNotNull(result);
        AiMethodSuggestion auth = findMethod(result, "test_auth");
        AiMethodSuggestion utility = findMethod(result, "test_utility");

        assertTrue(auth.securityRelevant());
        assertEquals(List.of("class-tag"), auth.tags());

        assertFalse(utility.securityRelevant());
        assertEquals("utility only", utility.reason());
    }

    // -------------------------------------------------------------------------
    // Partial override — null fields keep AI values
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("override with only securityRelevant set keeps AI tags and reason")
    @Tag("positive")
    void partial_nullFieldsKeepAiValues(@TempDir Path tmp) throws IOException {
        Path file = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.FooTest
                    method: test_one
                    securityRelevant: true
                """);

        ClassificationOverride co = ClassificationOverride.load(file);
        AiClassSuggestion result = co.apply("com.acme.FooTest",
                classSuggestion("com.acme.FooTest",
                        List.of(methodSuggestion("test_one", false, List.of("crypto"), "ai-reason", 0.3))),
                List.of("test_one"));

        assertNotNull(result);
        AiMethodSuggestion m = result.methods().get(0);
        assertTrue(m.securityRelevant());
        assertEquals(List.of("crypto"), m.tags());      // kept from AI
        assertEquals("ai-reason", m.reason());           // kept from AI
        assertEquals(1.0, m.confidence(), 1e-9);
    }

    // -------------------------------------------------------------------------
    // Static mode synthesis (no AI suggestion)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("override applied to null suggestion synthesizes AiClassSuggestion from override fields")
    @Tag("positive")
    void staticMode_synthesizesClassSuggestion(@TempDir Path tmp) throws IOException {
        Path file = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.CryptoTest
                    method: test_encrypt
                    securityRelevant: true
                    tags: [crypto]
                    reason: "human-verified crypto test"
                """);

        ClassificationOverride co = ClassificationOverride.load(file);
        AiClassSuggestion result = co.apply("com.acme.CryptoTest", null, List.of("test_encrypt"));

        assertNotNull(result, "override should synthesize result even when AI suggestion is null");
        AiMethodSuggestion m = findMethod(result, "test_encrypt");
        assertTrue(m.securityRelevant());
        assertEquals(List.of("crypto"), m.tags());
        assertEquals("human-verified crypto test", m.reason());
        assertEquals(1.0, m.confidence(), 1e-9);
    }

    @Test
    @DisplayName("no override for class + null suggestion returns null (static mode, no annotation)")
    @Tag("positive")
    void staticMode_noOverride_returnsNull(@TempDir Path tmp) throws IOException {
        Path file = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.OtherTest
                    method: test_x
                    securityRelevant: true
                """);

        ClassificationOverride co = ClassificationOverride.load(file);
        // Different fqcn → no overrides → null in, null out
        assertNull(co.apply("com.acme.UnrelatedTest", null, List.of("test_y")));
    }

    // -------------------------------------------------------------------------
    // Unknown method is silently ignored
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("override targeting a method name not in methodNames is silently ignored")
    @Tag("positive")
    void unknownMethod_isIgnored(@TempDir Path tmp) throws IOException {
        Path file = writeOverride(tmp, """
                overrides:
                  - fqcn: com.acme.FooTest
                    method: ghost_method
                    securityRelevant: true
                """);

        ClassificationOverride co = ClassificationOverride.load(file);
        // ghost_method is not in methodNames → override is silently skipped;
        // because the class-level fallback is also null, test_real gets a neutral record.
        AiClassSuggestion result = co.apply("com.acme.FooTest",
                classSuggestion("com.acme.FooTest",
                        List.of(methodSuggestion("test_real", false, List.of(), null, 0.0))),
                List.of("test_real"));

        assertNotNull(result);
        AiMethodSuggestion m = findMethod(result, "test_real");
        // class-level: null, method-level: null for test_real → keep base AI value
        assertFalse(m.securityRelevant());
    }

    // -------------------------------------------------------------------------
    // Blank fqcn entry is skipped
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("override entry with blank fqcn is skipped without error")
    @Tag("positive")
    void blankFqcn_isSkipped(@TempDir Path tmp) throws IOException {
        Path file = writeOverride(tmp, """
                overrides:
                  - fqcn: ""
                    method: anything
                    securityRelevant: true
                  - fqcn: com.acme.GoodTest
                    method: test_ok
                    securityRelevant: true
                """);

        ClassificationOverride co = ClassificationOverride.load(file);
        assertTrue(co.hasOverridesFor("com.acme.GoodTest"));
        assertFalse(co.hasOverridesFor(""));
    }

    // -------------------------------------------------------------------------
    // Empty overrides list
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("YAML file with empty overrides list loads without error and is a no-op")
    @Tag("positive")
    void emptyOverridesList_loadsAsNoOp(@TempDir Path tmp) throws IOException {
        Path file = writeOverride(tmp, "overrides: []\n");
        ClassificationOverride co = ClassificationOverride.load(file);
        assertFalse(co.hasOverridesFor("com.acme.FooTest"));
        assertNull(co.apply("com.acme.FooTest", null, List.of("test_one")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Path writeOverride(Path dir, String yaml) throws IOException {
        Path file = dir.resolve("overrides.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }

    private static AiClassSuggestion classSuggestion(String fqcn, List<AiMethodSuggestion> methods) {
        return new AiClassSuggestion(fqcn, null, null, null, methods);
    }

    private static AiMethodSuggestion methodSuggestion(String name, boolean securityRelevant,
            List<String> tags, String reason, double confidence) {
        return new AiMethodSuggestion(name, securityRelevant, null, tags, reason, confidence, 0.0);
    }

    private static AiMethodSuggestion findMethod(AiClassSuggestion suggestion, String name) {
        return suggestion.methods().stream()
                .filter(m -> m.methodName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Method not found: " + name));
    }
}
