package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutputEmitter}.
 *
 * <p>
 * This class verifies CSV escaping, metadata emission, CSV header generation,
 * and record emission in both CSV and plain-text output modes, with and without
 * AI enrichment and content-hash columns.
 * </p>
 */
@Tag("unit")
@Tag("output-emitter")
class OutputEmitterTest {

    // -------------------------------------------------------------------------
    // csvEscape
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("csvEscape returns empty string for null input")
    @Tag("edge-case")
    void csvEscape_null_returnsEmptyString() {
        assertEquals("", OutputEmitter.csvEscape(null));
    }

    @Test
    @DisplayName("csvEscape returns empty string for empty input")
    @Tag("edge-case")
    void csvEscape_emptyString_returnsEmptyString() {
        assertEquals("", OutputEmitter.csvEscape(""));
    }

    @Test
    @DisplayName("csvEscape returns plain value unchanged when no special characters")
    @Tag("positive")
    void csvEscape_plainValue_returnsUnchanged() {
        assertEquals("plain", OutputEmitter.csvEscape("plain"));
    }

    @Test
    @DisplayName("csvEscape wraps value containing comma in double quotes")
    @Tag("positive")
    void csvEscape_withComma_wrapsInQuotes() {
        assertEquals("\"with,comma\"", OutputEmitter.csvEscape("with,comma"));
    }

    @Test
    @DisplayName("csvEscape wraps value containing double quote in double quotes and doubles the embedded quote")
    @Tag("positive")
    void csvEscape_withQuote_wrapsAndDoublesQuote() {
        assertEquals("\"has\"\"quote\"", OutputEmitter.csvEscape("has\"quote"));
    }

    @Test
    @DisplayName("csvEscape wraps value containing newline in double quotes")
    @Tag("positive")
    void csvEscape_withNewline_wrapsInQuotes() {
        assertEquals("\"line\nnewline\"", OutputEmitter.csvEscape("line\nnewline"));
    }

    @Test
    @DisplayName("csvEscape wraps value containing carriage return in double quotes")
    @Tag("positive")
    void csvEscape_withCarriageReturn_wrapsInQuotes() {
        assertEquals("\"cr\rchar\"", OutputEmitter.csvEscape("cr\rchar"));
    }

    @Test
    @DisplayName("csvEscape handles value with both comma and embedded quote correctly")
    @Tag("positive")
    void csvEscape_withCommaAndQuote_handledCorrectly() {
        assertEquals("\"both,\"\"special\"\"\"", OutputEmitter.csvEscape("both,\"special\""));
    }

    @Test
    @DisplayName("csvEscape returns plain value unchanged when no special characters in multi-word string")
    @Tag("positive")
    void csvEscape_noSpecialChars_returnsUnchanged() {
        assertEquals("no special chars", OutputEmitter.csvEscape("no special chars"));
    }

    // -------------------------------------------------------------------------
    // emitMetadata
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emitMetadata emits exactly three lines in '# key: value' format")
    @Tag("positive")
    void emitMetadata_emitsThreeLinesInKeyValueFormat() {
        String output = captureOutput(emitter -> emitter.emitMetadata("1.0.0", "2025-01-01T00:00:00Z", "default"));
        String[] lines = output.split("\\R");
        assertEquals(3, lines.length);
        assertEquals("# tool_version: 1.0.0", lines[0]);
        assertEquals("# scan_timestamp: 2025-01-01T00:00:00Z", lines[1]);
        assertEquals("# taxonomy: default", lines[2]);
    }

    // -------------------------------------------------------------------------
    // emitCsvHeader
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emitCsvHeader in CSV mode with no AI and no hash emits minimal header")
    @Tag("positive")
    void emitCsvHeader_csvMode_noAiNoHash_emitsMinimalHeader() {
        String output = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                false, false, false);
        assertEquals("fqcn,method,loc,tags", output.trim());
    }

    @Test
    @DisplayName("emitCsvHeader in CSV mode with contentHash includes content_hash column after tags")
    @Tag("positive")
    void emitCsvHeader_csvMode_withContentHash_includesContentHashColumn() {
        String output = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                false, false, true);
        assertEquals("fqcn,method,loc,tags,content_hash", output.trim());
    }

    @Test
    @DisplayName("emitCsvHeader in CSV mode with AI enabled (no confidence) includes ai columns")
    @Tag("positive")
    void emitCsvHeader_csvMode_aiEnabledNoConfidence_includesAiColumns() {
        String output = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                true, false, false);
        String header = output.trim();
        assertTrue(header.contains("ai_security_relevant"), header);
        assertTrue(header.contains("ai_display_name"), header);
        assertTrue(header.contains("ai_tags"), header);
        assertTrue(header.contains("ai_reason"), header);
        assertFalse(header.contains("ai_confidence"), header);
    }

    @Test
    @DisplayName("emitCsvHeader in CSV mode with AI and confidence includes ai_confidence column")
    @Tag("positive")
    void emitCsvHeader_csvMode_aiAndConfidence_includesConfidenceColumn() {
        String output = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                true, true, false);
        assertTrue(output.contains("ai_confidence"), output);
    }

    @Test
    @DisplayName("emitCsvHeader in PLAIN mode emits nothing")
    @Tag("positive")
    void emitCsvHeader_plainMode_emitsNothing() {
        String output = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.PLAIN),
                false, false, false);
        assertEquals("", output.trim());
    }

    @Test
    @DisplayName("emitCsvHeader in SARIF mode emits nothing")
    @Tag("positive")
    void emitCsvHeader_sarifMode_emitsNothing() {
        String output = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.SARIF),
                false, false, false);
        assertEquals("", output.trim());
    }

    // -------------------------------------------------------------------------
    // emit() – CSV mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emit CSV mode, null suggestion, no AI, no hash, empty tags → basic CSV line with empty tags field")
    @Tag("positive")
    void emit_csvMode_nullSuggestionNoAiNoHashEmptyTags_correctLine() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 5, null, List.of(), null),
                false, false, false);
        assertEquals("com.acme.FooTest,testFoo,5,", output.trim());
    }

    @Test
    @DisplayName("emit CSV mode, null suggestion, no AI, no hash, with tags → tags joined by semicolons")
    @Tag("positive")
    void emit_csvMode_withTags_tagsJoinedBySemicolon() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 3, null,
                        List.of("security", "auth"), null),
                false, false, false);
        assertEquals("com.acme.FooTest,testFoo,3,security;auth", output.trim());
    }

    @Test
    @DisplayName("emit CSV mode, with contentHash → hash column present after tags")
    @Tag("positive")
    void emit_csvMode_withContentHash_hashColumnPresent() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 3, "abc123", List.of(), null),
                false, false, true);
        assertEquals("com.acme.FooTest,testFoo,3,,abc123", output.trim());
    }

    @Test
    @DisplayName("emit CSV mode, AI suggestion present, no confidence → ai columns filled with suggestion values")
    @Tag("positive")
    void emit_csvMode_aiSuggestionNoConfidence_aiColumnsFilled() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", true, "SECURITY: auth - login", List.of("security", "auth"), "Validates auth", 0.9, 0.0);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), suggestion),
                true, false, false);
        String line = output.trim();
        assertTrue(line.contains("true"), line);
        assertTrue(line.contains("SECURITY: auth - login"), line);
        assertTrue(line.contains("security;auth"), line);
        assertTrue(line.contains("Validates auth"), line);
        // interaction score present, no confidence column
        assertTrue(line.endsWith(",0.0"), line);
        assertFalse(line.endsWith(",0.9"), line);
    }

    @Test
    @DisplayName("emit CSV mode, AI suggestion present, with confidence → confidence appended as formatted decimal")
    @Tag("positive")
    void emit_csvMode_aiSuggestionWithConfidence_confidenceAppended() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", true, "SECURITY: auth", List.of("security"), "reason", 0.9, 0.0);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), suggestion),
                true, true, false);
        // column order: ...,ai_reason,ai_interaction_score,ai_confidence
        assertTrue(output.trim().endsWith(",0.9"), output);
    }

    @Test
    @DisplayName("emit CSV mode, null suggestion with AI enabled → all AI columns are empty strings")
    @Tag("positive")
    void emit_csvMode_nullSuggestionAiEnabled_allAiColumnsEmpty() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), null),
                true, false, false);
        // Format: fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score
        assertEquals("com.acme.FooTest,testFoo,5,,,,,,", output.trim());
    }

    // -------------------------------------------------------------------------
    // emit() – PLAIN mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emit PLAIN mode, null suggestion, no AI, empty tags → correct plain line with '-' for tags")
    @Tag("positive")
    void emit_plainMode_nullSuggestionNoAiEmptyTags_tagsAsDash() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.FooTest", "testFoo", 7, null,
                        List.of(), null),
                false, false, false);
        assertEquals("com.acme.FooTest, testFoo, LOC=7, TAGS=-", output.trim());
    }

    @Test
    @DisplayName("emit PLAIN mode with tags, AI suggestion, with confidence → all fields present in output")
    @Tag("positive")
    void emit_plainMode_withTagsAiSuggestionAndConfidence_allFieldsPresent() {
        // Use confidence=1.0 to avoid floating-point rounding ambiguity in %.1f formatting.
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", true, "SECURITY: auth - login", List.of("security", "auth"), "Validates auth", 1.0, 0.0);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.FooTest", "testFoo", 5, null,
                        List.of("fast"), suggestion),
                true, true, false);
        String line = output.trim();
        assertTrue(line.contains("LOC=5"), line);
        assertTrue(line.contains("TAGS=fast"), line);
        assertTrue(line.contains("AI_SECURITY=true"), line);
        assertTrue(line.contains("AI_DISPLAY=SECURITY: auth - login"), line);
        assertTrue(line.contains("AI_TAGS=security;auth"), line);
        assertTrue(line.contains("AI_REASON=Validates auth"), line);
        assertTrue(line.contains("AI_CONFIDENCE=1.0"), line);
    }

    @Test
    @DisplayName("emit PLAIN mode, null suggestion, with contentHash, AI disabled → HASH field present")
    @Tag("positive")
    void emit_plainMode_nullSuggestionWithContentHash_hashFieldPresent() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.FooTest", "testFoo", 3, "deadbeef",
                        List.of(), null),
                false, false, true);
        assertTrue(output.contains("HASH=deadbeef"), output);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface EmitterConsumer {
        void accept(OutputEmitter emitter) throws Exception;
    }

    private static String captureOutput(EmitterConsumer consumer) {
        return captureOutput(consumer, false, false, false);
    }

    private static String captureOutput(EmitterConsumer consumer,
            boolean aiEnabled, boolean confidenceEnabled, boolean contentHashEnabled) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            OutputEmitter emitter = new OutputEmitter(pw, aiEnabled, confidenceEnabled, contentHashEnabled);
            consumer.accept(emitter);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
