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
import org.egothor.methodatlas.emit.OutputEmitter;
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
    @Tag("security")
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
    @Tag("security")
    void csvEscape_withComma_wrapsInQuotes() {
        assertEquals("\"with,comma\"", OutputEmitter.csvEscape("with,comma"));
    }

    @Test
    @DisplayName("csvEscape wraps value containing double quote in double quotes and doubles the embedded quote")
    @Tag("positive")
    @Tag("security")
    void csvEscape_withQuote_wrapsAndDoublesQuote() {
        assertEquals("\"has\"\"quote\"", OutputEmitter.csvEscape("has\"quote"));
    }

    @Test
    @DisplayName("csvEscape wraps value containing newline in double quotes")
    @Tag("positive")
    @Tag("security")
    void csvEscape_withNewline_wrapsInQuotes() {
        assertEquals("\"line\nnewline\"", OutputEmitter.csvEscape("line\nnewline"));
    }

    @Test
    @DisplayName("csvEscape wraps value containing carriage return in double quotes")
    @Tag("positive")
    @Tag("security")
    void csvEscape_withCarriageReturn_wrapsInQuotes() {
        assertEquals("\"cr\rchar\"", OutputEmitter.csvEscape("cr\rchar"));
    }

    @Test
    @DisplayName("csvEscape handles value with both comma and embedded quote correctly")
    @Tag("positive")
    @Tag("security")
    void csvEscape_withCommaAndQuote_handledCorrectly() {
        assertEquals("\"both,\"\"special\"\"\"", OutputEmitter.csvEscape("both,\"special\""));
    }

    @Test
    @DisplayName("csvEscape returns plain value unchanged when no special characters in multi-word string")
    @Tag("positive")
    void csvEscape_noSpecialChars_returnsUnchanged() {
        assertEquals("no special chars", OutputEmitter.csvEscape("no special chars"));
    }

    @Test
    @DisplayName("csvEscape wraps value starting with '=' in double quotes to prevent formula injection")
    @Tag("positive")
    @Tag("security")
    void csvEscape_withEqualsPrefix_wrapsInQuotes() {
        assertEquals("\"=SUM(A1:A10)\"", OutputEmitter.csvEscape("=SUM(A1:A10)"));
    }

    @Test
    @DisplayName("csvEscape wraps value starting with '+' in double quotes to prevent formula injection")
    @Tag("positive")
    @Tag("security")
    void csvEscape_withPlusPrefix_wrapsInQuotes() {
        assertEquals("\"+cmd|' /C calc'!A0\"", OutputEmitter.csvEscape("+cmd|' /C calc'!A0"));
    }

    @Test
    @DisplayName("csvEscape wraps value starting with '@' in double quotes to prevent formula injection")
    @Tag("positive")
    @Tag("security")
    void csvEscape_withAtPrefix_wrapsInQuotes() {
        assertEquals("\"@SUM(1+1)\"", OutputEmitter.csvEscape("@SUM(1+1)"));
    }

    @Test
    @DisplayName("csvEscape wraps value starting with '-' in double quotes to prevent formula injection")
    @Tag("positive")
    @Tag("security")
    void csvEscape_withMinusPrefix_wrapsInQuotes() {
        assertEquals("\"-2+3\"", OutputEmitter.csvEscape("-2+3"));
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
        assertEquals("fqcn,method,loc,tags,display_name", output.trim());
    }

    @Test
    @DisplayName("emitCsvHeader in CSV mode with contentHash includes content_hash column after tags")
    @Tag("positive")
    void emitCsvHeader_csvMode_withContentHash_includesContentHashColumn() {
        String output = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                false, false, true);
        assertEquals("fqcn,method,loc,tags,display_name,content_hash", output.trim());
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
    @DisplayName("emitCsvHeader in CSV mode with drift-detect appends tag_ai_drift as last column")
    @Tag("positive")
    void emitCsvHeader_csvMode_withDriftDetect_appendsTagAiDriftColumn() {
        String header = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                true, false, false, true).trim();
        assertTrue(header.endsWith(",tag_ai_drift"),
                "tag_ai_drift should be the last column: " + header);
    }

    @Test
    @DisplayName("emitCsvHeader in CSV mode with drift and confidence: confidence before tag_ai_drift")
    @Tag("positive")
    void emitCsvHeader_csvMode_driftAndConfidence_confidenceBeforeDrift() {
        String header = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                true, true, false, true).trim();
        int confidenceIdx = header.indexOf("ai_confidence");
        int driftIdx = header.indexOf("tag_ai_drift");
        assertTrue(confidenceIdx >= 0 && driftIdx >= 0 && confidenceIdx < driftIdx,
                "ai_confidence must appear before tag_ai_drift: " + header);
    }

    @Test
    @DisplayName("emitCsvHeader in CSV mode without drift-detect: tag_ai_drift column absent")
    @Tag("positive")
    void emitCsvHeader_csvMode_withoutDriftDetect_tagAiDriftAbsent() {
        String header = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                true, false, false, false).trim();
        assertFalse(header.contains("tag_ai_drift"),
                "tag_ai_drift should not appear when drift-detect is disabled: " + header);
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
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 5, null, List.of(), "", null, null),
                false, false, false);
        assertEquals("com.acme.FooTest,testFoo,5,,", output.trim());
    }

    @Test
    @DisplayName("emit CSV mode, null suggestion, no AI, no hash, with tags → tags joined by semicolons")
    @Tag("positive")
    void emit_csvMode_withTags_tagsJoinedBySemicolon() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 3, null,
                        List.of("security", "auth"), "", null, null),
                false, false, false);
        assertEquals("com.acme.FooTest,testFoo,3,security;auth,", output.trim());
    }

    @Test
    @DisplayName("emit CSV mode, with contentHash → hash column present after tags")
    @Tag("positive")
    void emit_csvMode_withContentHash_hashColumnPresent() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 3, "abc123", List.of(), "", null, null),
                false, false, true);
        assertEquals("com.acme.FooTest,testFoo,3,,,abc123", output.trim());
    }

    @Test
    @DisplayName("emit CSV mode, AI suggestion present, no confidence → ai columns filled with suggestion values")
    @Tag("positive")
    void emit_csvMode_aiSuggestionNoConfidence_aiColumnsFilled() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", true, "SECURITY: auth - login", List.of("security", "auth"), "Validates auth", 0.9, 0.0);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), "", suggestion, null),
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
                        List.of(), "", suggestion, null),
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
                        List.of(), "", null, null),
                true, false, false);
        // Format: fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score
        assertEquals("com.acme.FooTest,testFoo,5,,,,,,,", output.trim());
    }

    // -------------------------------------------------------------------------
    // emit() – CSV mode with drift detection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emit CSV with drift-detect: 'none' when source tag and AI both agree security-relevant")
    @Tag("positive")
    void emit_csvMode_driftNone_whenBothAgreeSecurityRelevant() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Auth test", List.of("auth"), "reason", 0.9, 0.1);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.AuthTest", "testLogin", 5, null,
                        List.of("security"), "", suggestion, null),
                true, false, false, true).trim();
        assertTrue(output.endsWith(",none"), "Last CSV field should be 'none' when both agree: " + output);
    }

    @Test
    @DisplayName("emit CSV with drift-detect: 'ai-only' when AI says security-relevant but no source tag")
    @Tag("positive")
    void emit_csvMode_driftAiOnly_whenAiSecurityButNoSourceTag() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Auth test", List.of("auth"), "reason", 0.9, 0.1);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.AuthTest", "testLogin", 5, null,
                        List.of(), "", suggestion, null),
                true, false, false, true).trim();
        assertTrue(output.endsWith(",ai-only"), "Last CSV field should be 'ai-only': " + output);
    }

    @Test
    @DisplayName("emit CSV with drift-detect: 'tag-only' when source tag present but AI disagrees")
    @Tag("positive")
    void emit_csvMode_driftTagOnly_whenSourceTagButAiDisagrees() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", false, "Format check", List.of(), "Not security", 0.1, 0.0);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 4, null,
                        List.of("security"), "", suggestion, null),
                true, false, false, true).trim();
        assertTrue(output.endsWith(",tag-only"), "Last CSV field should be 'tag-only': " + output);
    }

    @Test
    @DisplayName("emit CSV with drift-detect: empty drift cell when suggestion is null")
    @Tag("edge-case")
    void emit_csvMode_driftEmpty_whenSuggestionNull() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 4, null,
                        List.of("security"), "", null, null),
                true, false, false, true).trim();
        assertTrue(output.endsWith(","), "Drift cell should be empty when suggestion is null: " + output);
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
                        List.of(), "", null, null),
                false, false, false);
        assertEquals("com.acme.FooTest, testFoo, LOC=7, TAGS=-, DISPLAY=-", output.trim());
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
                        List.of("fast"), "", suggestion, null),
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
                        List.of(), "", null, null),
                false, false, true);
        assertTrue(output.contains("HASH=deadbeef"), output);
    }

    @Test
    @DisplayName("emit PLAIN with drift-detect: TAG_AI_DRIFT=none when both sources agree")
    @Tag("positive")
    void emit_plainMode_driftNone_tokenPresent() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Auth", List.of("auth"), "reason", 0.9, 0.1);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.AuthTest", "testLogin", 5, null,
                        List.of("security"), "", suggestion, null),
                true, false, false, true).trim();
        assertTrue(output.contains("TAG_AI_DRIFT=none"), "Should contain TAG_AI_DRIFT=none: " + output);
    }

    @Test
    @DisplayName("emit PLAIN with drift-detect: TAG_AI_DRIFT=ai-only when AI security but no source tag")
    @Tag("positive")
    void emit_plainMode_driftAiOnly_tokenPresent() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Auth", List.of("auth"), "reason", 0.9, 0.0);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.AuthTest", "testLogin", 5, null,
                        List.of(), "", suggestion, null),
                true, false, false, true).trim();
        assertTrue(output.contains("TAG_AI_DRIFT=ai-only"), "Should contain TAG_AI_DRIFT=ai-only: " + output);
    }

    @Test
    @DisplayName("emit PLAIN with drift-detect: TAG_AI_DRIFT=tag-only when source tag but AI disagrees")
    @Tag("positive")
    void emit_plainMode_driftTagOnly_tokenPresent() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", false, "Check", List.of(), "Not security", 0.1, 0.0);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.FooTest", "testFoo", 4, null,
                        List.of("security"), "", suggestion, null),
                true, false, false, true).trim();
        assertTrue(output.contains("TAG_AI_DRIFT=tag-only"), "Should contain TAG_AI_DRIFT=tag-only: " + output);
    }

    @Test
    @DisplayName("emit PLAIN without drift-detect: TAG_AI_DRIFT token absent")
    @Tag("positive")
    void emit_plainMode_noDriftDetect_tokenAbsent() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", true, "Auth", List.of("auth"), "reason", 0.9, 0.0);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.AuthTest", "testLogin", 5, null,
                        List.of(), "", suggestion, null),
                true, false, false, false).trim();
        assertFalse(output.contains("TAG_AI_DRIFT"), "TAG_AI_DRIFT should be absent when drift-detect is off: " + output);
    }

    // -------------------------------------------------------------------------
    // emit() – source_root column / SRCROOT token
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emitCsvHeader with emitSourceRoot includes source_root column after display_name")
    @Tag("positive")
    void emitCsvHeader_withEmitSourceRoot_includesSourceRootColumn() {
        String header = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                false, false, false, false, true).trim();
        int displayIdx = header.indexOf("display_name");
        int sourceRootIdx = header.indexOf("source_root");
        assertTrue(sourceRootIdx > 0, "source_root column should be present: " + header);
        assertTrue(displayIdx < sourceRootIdx,
                "source_root must appear after display_name: " + header);
    }

    @Test
    @DisplayName("emitCsvHeader with emitSourceRoot: source_root appears before content_hash when both enabled")
    @Tag("positive")
    void emitCsvHeader_withEmitSourceRootAndContentHash_sourceRootBeforeContentHash() {
        String header = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                false, false, true, false, true).trim();
        int sourceRootIdx = header.indexOf("source_root");
        int contentHashIdx = header.indexOf("content_hash");
        assertTrue(sourceRootIdx > 0 && contentHashIdx > 0,
                "Both columns must be present: " + header);
        assertTrue(sourceRootIdx < contentHashIdx,
                "source_root must appear before content_hash: " + header);
    }

    @Test
    @DisplayName("emitCsvHeader without emitSourceRoot: source_root column absent")
    @Tag("positive")
    void emitCsvHeader_withoutEmitSourceRoot_sourceRootAbsent() {
        String header = captureOutput(emitter -> emitter.emitCsvHeader(OutputMode.CSV),
                false, false, false).trim();
        assertFalse(header.contains("source_root"),
                "source_root should not appear when -emit-source-root is not set: " + header);
    }

    @Test
    @DisplayName("emit CSV with emitSourceRoot: source_root value appears in data row")
    @Tag("positive")
    void emit_csvMode_withEmitSourceRoot_sourceRootValuePresent() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), "", null, "module-a/src/test/java/"),
                false, false, false, false, true).trim();
        assertTrue(output.contains("module-a/src/test/java/"),
                "source_root value should appear in CSV row: " + output);
    }

    @Test
    @DisplayName("emit PLAIN with emitSourceRoot: SRCROOT token appears in output line")
    @Tag("positive")
    void emit_plainMode_withEmitSourceRoot_srcRootTokenPresent() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), "", null, "module-b/src/test/java/"),
                false, false, false, false, true).trim();
        assertTrue(output.contains("SRCROOT=module-b/src/test/java/"),
                "SRCROOT token should appear in plain output: " + output);
    }

    @Test
    @DisplayName("emit PLAIN with emitSourceRoot and empty sourceRoot: SRCROOT shows '-'")
    @Tag("positive")
    void emit_plainMode_withEmitSourceRootEmptyPath_srcRootShowsDash() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), "", null, ""),
                false, false, false, false, true).trim();
        assertTrue(output.contains("SRCROOT=-"),
                "SRCROOT should be '-' when scan root is the CWD: " + output);
    }

    @Test
    @DisplayName("emit CSV without emitSourceRoot: source_root column absent from row even when sourceRoot non-null")
    @Tag("positive")
    void emit_csvMode_withoutEmitSourceRoot_sourceRootAbsent() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), "", null, "module-a/src/test/java/"),
                false, false, false).trim();
        assertFalse(output.contains("module-a"),
                "source_root should not appear in CSV row when flag is off: " + output);
    }

    // -------------------------------------------------------------------------
    // Edge cases — blank/whitespace reason in AI suggestion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emit PLAIN mode: blank-whitespace reason shows '-' for AI_REASON")
    @Tag("edge-case")
    void emit_plainMode_blankReason_reasonShowsDash() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", true, "SECURITY: auth", List.of("security", "auth"), "   ", 0.9, 0.0);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), null, suggestion, null),
                true, false, false).trim();
        assertTrue(output.contains("AI_REASON=-"),
                "blank reason should show '-' in plain output: " + output);
    }

    @Test
    @DisplayName("emit CSV mode: null reason in AI suggestion produces empty ai_reason field")
    @Tag("edge-case")
    void emit_csvMode_nullReason_reasonFieldEmpty() {
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testFoo", true, "SECURITY: auth", List.of("security", "auth"), null, 0.9, 0.0);
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), null, suggestion, null),
                true, false, false).trim();
        // ai_tags is "security;auth", ai_reason is empty, ai_interaction_score follows
        // → "security;auth,,0.0" — two consecutive commas indicate empty ai_reason
        assertTrue(output.contains("security;auth,,"),
                "null reason should produce empty CSV field between ai_tags and ai_interaction_score: " + output);
    }

    @Test
    @DisplayName("emit PLAIN mode: null sourceRoot with emitSourceRoot=true shows '-'")
    @Tag("edge-case")
    void emit_plainMode_nullSourceRoot_withEmitSourceRoot_showsDash() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.PLAIN, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), null, null, null),
                false, false, false, false, true).trim();
        assertTrue(output.contains("SRCROOT=-"),
                "null sourceRoot with emitSourceRoot=true should show '-': " + output);
    }

    @Test
    @DisplayName("emit CSV mode: null sourceRoot with emitSourceRoot=true produces empty field")
    @Tag("edge-case")
    void emit_csvMode_nullSourceRoot_withEmitSourceRoot_emptyField() {
        String output = captureOutput(
                emitter -> emitter.emit(OutputMode.CSV, "com.acme.FooTest", "testFoo", 5, null,
                        List.of(), null, null, null),
                false, false, false, false, true).trim();
        // Column order: fqcn,method,loc,tags,display_name,source_root
        // null sourceRoot → csvEscape("") → "" → trailing empty field after display_name
        assertTrue(output.endsWith(","),
                "null sourceRoot with emitSourceRoot=true should produce trailing empty field: " + output);
    }

    @Test
    @DisplayName("csvEscape: single double-quote character is escaped as four double-quote chars")
    @Tag("edge-case")
    void csvEscape_singleQuoteOnly_escapedAndWrapped() {
        // input: "    → must quote because it contains a double-quote
        // escaped:  ""  → wrap: """"
        assertEquals("\"\"\"\"", OutputEmitter.csvEscape("\""),
                "single double-quote should become four double-quote chars (wrap + double)");
    }

    @Test
    @DisplayName("csvEscape: value with only special-prefix char (just '=') is wrapped in double quotes")
    @Tag("edge-case")
    void csvEscape_formulaPrefixOnly_wrapsEmptyBodyInQuotes() {
        assertEquals("\"=\"", OutputEmitter.csvEscape("="),
                "single '=' should be wrapped in double quotes");
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
        return captureOutput(consumer, aiEnabled, confidenceEnabled, contentHashEnabled, false);
    }

    private static String captureOutput(EmitterConsumer consumer,
            boolean aiEnabled, boolean confidenceEnabled, boolean contentHashEnabled, boolean driftDetect) {
        return captureOutput(consumer, aiEnabled, confidenceEnabled, contentHashEnabled, driftDetect, false);
    }

    private static String captureOutput(EmitterConsumer consumer,
            boolean aiEnabled, boolean confidenceEnabled, boolean contentHashEnabled, boolean driftDetect,
            boolean emitSourceRoot) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            OutputEmitter emitter = new OutputEmitter(pw, aiEnabled, confidenceEnabled, contentHashEnabled,
                    driftDetect, emitSourceRoot);
            consumer.accept(emitter);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
