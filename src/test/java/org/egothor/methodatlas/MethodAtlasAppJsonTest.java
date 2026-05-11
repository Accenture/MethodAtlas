package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Integration tests for {@code -json} output mode and {@code -min-confidence}
 * threshold filtering.
 */
@Tag("integration")
@Tag("json")
class MethodAtlasAppJsonTest {

    // -------------------------------------------------------------------------
    // JSON output mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-json mode produces a valid JSON array with one element per test method")
    @Tag("positive")
    void jsonMode_producesValidArray(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp("-json", tempDir.toString());
        JsonNode root = parse(output);

        assertTrue(root.isArray(), "Output must be a JSON array");
        assertEquals(5, root.size(), "SampleOneTest has 5 test methods");
    }

    @Test
    @DisplayName("-json mode records have required scalar fields")
    @Tag("positive")
    void jsonMode_recordsHaveRequiredFields(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp("-json", tempDir.toString());
        JsonNode root = parse(output);

        JsonNode alpha = findByMethod(root, "alpha");
        assertNotNull(alpha, "Method 'alpha' must be present");
        assertEquals("com.acme.tests.SampleOneTest", alpha.get("fqcn").asText());
        assertEquals("alpha", alpha.get("method").asText());
        assertEquals(8, alpha.get("loc").asInt());
        assertTrue(alpha.get("tags").isArray(), "tags must be a JSON array");
        assertTrue(alpha.get("tags").toString().contains("fast"), "tags must include 'fast'");
        assertTrue(alpha.get("tags").toString().contains("crypto"), "tags must include 'crypto'");
    }

    @Test
    @DisplayName("-json mode omits AI columns when AI is disabled")
    @Tag("positive")
    void jsonMode_noAi_omitsAiFields(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        String output = runApp("-json", tempDir.toString());
        JsonNode root = parse(output);

        JsonNode alpha = findByMethod(root, "alpha");
        assertNotNull(alpha);
        assertFalse(alpha.has("ai_security_relevant"), "ai_security_relevant must be absent without -ai");
        assertFalse(alpha.has("ai_tags"), "ai_tags must be absent without -ai");
        assertFalse(alpha.has("ai_confidence"), "ai_confidence must be absent without -ai");
    }

    @Test
    @DisplayName("-json mode with AI: ai_tags is a JSON array, not a semicolon string")
    @Tag("positive")
    void jsonMode_withAi_aiTagsIsArray(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> when(mock.suggestForClass(anyString(), anyString(), anyString(), any()))
                        .thenReturn(alphaSuggestion()))) {

            String output = runApp("-json", "-ai", tempDir.toString());
            JsonNode root = parse(output);

            JsonNode alpha = findByMethod(root, "alpha");
            assertNotNull(alpha);
            assertTrue(alpha.has("ai_security_relevant"));
            assertTrue(alpha.get("ai_security_relevant").isBoolean());
            assertTrue(alpha.get("ai_tags").isArray(), "ai_tags must be a JSON array");
            assertFalse(alpha.has("ai_confidence"), "ai_confidence absent without -ai-confidence");
        }
    }

    @Test
    @DisplayName("-json mode with AI and confidence: ai_confidence is a JSON number")
    @Tag("positive")
    void jsonMode_withAiAndConfidence_confidenceIsNumber(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> when(mock.suggestForClass(anyString(), anyString(), anyString(), any()))
                        .thenReturn(alphaSuggestion()))) {

            String output = runApp("-json", "-ai", "-ai-confidence", tempDir.toString());
            JsonNode root = parse(output);

            JsonNode alpha = findByMethod(root, "alpha");
            assertNotNull(alpha);
            assertTrue(alpha.has("ai_confidence"), "ai_confidence must be present with -ai-confidence");
            assertTrue(alpha.get("ai_confidence").isNumber(), "ai_confidence must be a JSON number");
        }
    }

    // -------------------------------------------------------------------------
    // -min-confidence filtering
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-min-confidence filters methods whose confidence is below threshold")
    @Tag("positive")
    void minConfidence_filtersLowConfidenceMethods(@TempDir Path tempDir) throws Exception {
        // Write a fixture with two methods so we can filter one
        String src = """
                package com.acme;
                import org.junit.jupiter.api.Test;
                public class TwoMethodTest {
                    @Test
                    public void highConfidenceMethod() {}
                    @Test
                    public void lowConfidenceMethod() {}
                }
                """;
        Files.writeString(tempDir.resolve("TwoMethodTest.java"), src, StandardCharsets.UTF_8);

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> when(mock.suggestForClass(anyString(), eq("com.acme.TwoMethodTest"),
                        anyString(), any())).thenReturn(twoMethodSuggestion()))) {

            // Without filter: both security-relevant methods appear
            String allOutput = runApp("-ai", "-ai-confidence", "-security-only", tempDir.toString());
            List<String> allLines = allOutput.lines().filter(l -> !l.isBlank()).toList();
            assertEquals(3, allLines.size(), "Expected header + 2 rows without filter");

            // With threshold 0.8: only highConfidenceMethod (confidence=0.9) passes
            String filteredOutput = runApp("-ai", "-ai-confidence", "-security-only",
                    "-min-confidence", "0.8", tempDir.toString());
            List<String> filteredLines = filteredOutput.lines().filter(l -> !l.isBlank()).toList();
            assertEquals(2, filteredLines.size(), "Expected header + 1 row above threshold");
            assertTrue(filteredLines.stream().anyMatch(l -> l.contains("highConfidenceMethod")),
                    "highConfidenceMethod (confidence=0.9) must pass threshold 0.8");
            assertFalse(filteredLines.stream().anyMatch(l -> l.contains("lowConfidenceMethod")),
                    "lowConfidenceMethod (confidence=0.3) must be filtered out");
        }
    }

    @Test
    @DisplayName("-min-confidence has no effect when -ai-confidence is not enabled")
    @Tag("positive")
    void minConfidence_noEffectWithoutAiConfidence(@TempDir Path tempDir) throws Exception {
        copyFixture(tempDir, "SampleOneTest.java");

        // Without -ai-confidence all confidence values are 0.0; filter must not drop everything
        String output = runApp("-json", "-min-confidence", "0.7", tempDir.toString());
        JsonNode root = parse(output);
        // All 5 methods must still appear — confidence filter is inactive without -ai-confidence
        assertEquals(5, root.size(), "All methods must appear when confidence scoring is disabled");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AiClassSuggestion alphaSuggestion() {
        AiMethodSuggestion alpha = new AiMethodSuggestion(
                "alpha", true, "SECURITY: crypto - test", List.of("security", "crypto"),
                "Tests crypto", 0.9, 0.0);
        AiMethodSuggestion beta = new AiMethodSuggestion(
                "beta", false, null, List.of(), "Not security-relevant", 0.0, 0.1);
        AiMethodSuggestion gamma = new AiMethodSuggestion(
                "gamma", false, null, List.of(), null, 0.0, 0.2);
        AiMethodSuggestion delta = new AiMethodSuggestion(
                "delta", false, null, List.of(), null, 0.0, 0.1);
        AiMethodSuggestion epsilon = new AiMethodSuggestion(
                "epsilon", false, null, List.of(), null, 0.0, 0.2);
        return new AiClassSuggestion("com.acme.tests.SampleOneTest", false, List.of(),
                null, List.of(alpha, beta, gamma, delta, epsilon));
    }

    private static AiClassSuggestion twoMethodSuggestion() {
        AiMethodSuggestion high = new AiMethodSuggestion(
                "highConfidenceMethod", true, "SECURITY: auth - high", List.of("security", "auth"),
                "High confidence security test", 0.9, 0.0);
        AiMethodSuggestion low = new AiMethodSuggestion(
                "lowConfidenceMethod", true, "SECURITY: auth - low", List.of("security", "auth"),
                "Low confidence security test", 0.3, 0.0);
        return new AiClassSuggestion("com.acme.TwoMethodTest", null, List.of(),
                null, List.of(high, low));
    }

    private static JsonNode findByMethod(JsonNode array, String methodName) {
        for (JsonNode node : array) {
            if (methodName.equals(node.path("method").asText())) {
                return node;
            }
        }
        return null;
    }

    private static JsonNode parse(String json) throws IOException {
        return JsonMapper.builder().build().readTree(json.trim());
    }

    private static String runApp(String... args) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(args, out);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static void copyFixture(Path destDir, String fileName) throws IOException {
        String resource = "/fixtures/" + fileName + ".txt";
        try (InputStream in = MethodAtlasAppJsonTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "Missing test resource: " + resource);
            Files.copy(in, destDir.resolve(fileName));
        }
    }
}
