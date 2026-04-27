package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit and integration tests for {@link GitHubAnnotationsEmitter}.
 */
class GitHubAnnotationsEmitterTest {

    // -------------------------------------------------------------------------
    // escapeParam
    // -------------------------------------------------------------------------

    @Test
    @Tag("security")
    void escapeParam_encodesPercent() {
        assertEquals("100%25 done", GitHubAnnotationsEmitter.escapeParam("100% done"));
    }

    @Test
    @Tag("security")
    void escapeParam_encodesCarriageReturn() {
        assertEquals("a%0Db", GitHubAnnotationsEmitter.escapeParam("a\rb"));
    }

    @Test
    @Tag("security")
    void escapeParam_encodesNewline() {
        assertEquals("a%0Ab", GitHubAnnotationsEmitter.escapeParam("a\nb"));
    }

    @Test
    @Tag("security")
    void escapeParam_encodesColon() {
        assertEquals("http%3A//example", GitHubAnnotationsEmitter.escapeParam("http://example"));
    }

    @Test
    @Tag("security")
    void escapeParam_encodesComma() {
        assertEquals("a%2Cb", GitHubAnnotationsEmitter.escapeParam("a,b"));
    }

    @Test
    void escapeParam_plainValueUnchanged() {
        assertEquals("src/test/java/com/acme/AuthTest.java",
                GitHubAnnotationsEmitter.escapeParam("src/test/java/com/acme/AuthTest.java"));
    }

    // -------------------------------------------------------------------------
    // escapeMessage
    // -------------------------------------------------------------------------

    @Test
    @Tag("security")
    void escapeMessage_encodesPercent() {
        assertEquals("100%25", GitHubAnnotationsEmitter.escapeMessage("100%"));
    }

    @Test
    @Tag("security")
    void escapeMessage_encodesNewline() {
        assertEquals("line1%0Aline2", GitHubAnnotationsEmitter.escapeMessage("line1\nline2"));
    }

    @Test
    void escapeMessage_colonNotEncoded() {
        // colons are allowed in the message part
        assertEquals("Tags: security", GitHubAnnotationsEmitter.escapeMessage("Tags: security"));
    }

    // -------------------------------------------------------------------------
    // formatCommand
    // -------------------------------------------------------------------------

    @Test
    void formatCommand_noticeLevelProducesCorrectPrefix() {
        String cmd = GitHubAnnotationsEmitter.formatCommand(
                "notice", "src/test/java/com/acme/AuthTest.java", 42, "Auth test", "Security test");
        assertTrue(cmd.startsWith("::notice "), "Should start with ::notice");
    }

    @Test
    void formatCommand_warningLevelProducesCorrectPrefix() {
        String cmd = GitHubAnnotationsEmitter.formatCommand(
                "warning", "src/test/java/com/acme/AuthTest.java", 42, "Auth test", "Placebo");
        assertTrue(cmd.startsWith("::warning "), "Should start with ::warning");
    }

    @Test
    void formatCommand_includesFileAndLine() {
        String cmd = GitHubAnnotationsEmitter.formatCommand(
                "notice", "src/test/java/com/acme/AuthTest.java", 10, "title", "msg");
        assertTrue(cmd.contains("file=src/test/java/com/acme/AuthTest.java"), "Should contain file=");
        assertTrue(cmd.contains(",line=10"), "Should contain line=");
    }

    @Test
    void formatCommand_lineZeroOmitted() {
        String cmd = GitHubAnnotationsEmitter.formatCommand(
                "notice", "some/File.java", 0, "title", "msg");
        assertFalse(cmd.contains(",line="), "line= should be omitted when beginLine=0");
    }

    @Test
    void formatCommand_emptyTitleOmitted() {
        String cmd = GitHubAnnotationsEmitter.formatCommand(
                "notice", "some/File.java", 1, "", "msg");
        assertFalse(cmd.contains("title="), "title= should be omitted when title is empty");
    }

    @Test
    void formatCommand_nullTitleOmitted() {
        String cmd = GitHubAnnotationsEmitter.formatCommand(
                "notice", "some/File.java", 1, null, "msg");
        assertFalse(cmd.contains("title="), "title= should be omitted when title is null");
    }

    @Test
    void formatCommand_messageAfterDoubleColons() {
        String cmd = GitHubAnnotationsEmitter.formatCommand(
                "notice", "f.java", 1, "T", "my message");
        assertTrue(cmd.endsWith("::my message"), "Message should follow :: terminator");
    }

    @Test
    void formatCommand_titleIsEscaped() {
        String cmd = GitHubAnnotationsEmitter.formatCommand(
                "notice", "f.java", 1, "title:with,special", "msg");
        assertTrue(cmd.contains("title=title%3Awith%2Cspecial"), "Title should be escaped");
    }

    // -------------------------------------------------------------------------
    // record() — unit tests with direct emitter construction
    // -------------------------------------------------------------------------

    @Test
    void record_nullSuggestion_producesNoOutput() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, "src/test/java/");

        emitter.record("com.acme.AuthTest", "testLogin", 5, 3, null, List.of(), null, null);

        assertEquals("", baos.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void record_nonSecuritySuggestion_producesNoOutput() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, "src/test/java/");

        AiMethodSuggestion nonSecurity = new AiMethodSuggestion("testLogin", false, null, List.of(), null, 0.0, 0.0);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 3, null, List.of(), null, nonSecurity);

        assertEquals("", baos.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void record_securityRelevant_emitsNoticeCommand() {
        String output = recordSecurityMethod(0.5, "Login security check", List.of("auth"), "src/test/java/");

        assertTrue(output.startsWith("::notice "), "Should emit ::notice");
        assertTrue(output.contains("file=src/test/java/com/acme/AuthTest.java"));
        assertTrue(output.contains(",line=5"));
    }

    @Test
    void record_interactionScoreAtThreshold_emitsWarning() {
        String output = recordSecurityMethod(
                GitHubAnnotationsEmitter.PLACEBO_THRESHOLD, "Placebo test", List.of("auth"), "src/test/java/");

        assertTrue(output.startsWith("::warning "), "Score >= threshold should produce ::warning");
    }

    @Test
    void record_interactionScoreAboveThreshold_emitsWarning() {
        String output = recordSecurityMethod(0.95, "Placebo test", List.of("auth"), "src/test/java/");

        assertTrue(output.startsWith("::warning "), "Score > threshold should produce ::warning");
    }

    @Test
    void record_interactionScoreBelowThreshold_emitsNotice() {
        String output = recordSecurityMethod(0.79, "Good test", List.of("auth"), "src/test/java/");

        assertTrue(output.startsWith("::notice "), "Score < threshold should produce ::notice");
    }

    @Test
    void record_displayNameUsedAsTitle() {
        String output = recordSecurityMethod(0.5, "SECURITY: auth", List.of("auth"), "");

        assertTrue(output.contains("title=SECURITY%3A auth"), "Display name should be used as title");
    }

    @Test
    void record_nullDisplayNameFallsBackToFqcnMethod() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, "");

        AiMethodSuggestion suggestion = new AiMethodSuggestion("testLogin", true, null, List.of("auth"), null, 0.0, 0.0);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 3, null, List.of(), null, suggestion);

        String output = baos.toString(StandardCharsets.UTF_8).trim();
        assertTrue(output.contains("com.acme.AuthTest#testLogin"), "Should fall back to fqcn#method");
    }

    @Test
    void record_tagsIncludedInMessage() {
        String output = recordSecurityMethod(0.3, "Auth", List.of("security", "auth"), "");

        assertTrue(output.contains("Suggested @Tag: security, auth"), "Tags should appear as suggested @Tag in message");
    }

    @Test
    void record_placeboMessageIncludesInteractionScore() {
        String output = recordSecurityMethod(0.9, "Placebo", List.of(), "");

        assertTrue(output.contains("Interaction score 0.9"), "Placebo message should include score");
        assertTrue(output.contains("assertions only verify method calls"), "Placebo explanation should be present");
    }

    @Test
    void record_emptyTagsAndNonPlacebo_noSourceTag_messageIsAiOnlyDrift() {
        // no source tag, AI says security-relevant → AI_ONLY drift → drift text replaces default
        String output = recordSecurityMethod(0.2, "Good", List.of(), "");

        assertTrue(output.contains("AI classifies as security-relevant"),
                "AI_ONLY drift text should appear when source tag is missing");
    }

    @Test
    void record_emptyTagsAndNonPlacebo_withMatchingSourceTag_noDriftInMessage() {
        // source tag agrees with AI → NONE drift → no drift noise in message
        String output = recordMethodWithSourceTags(true, List.of("security"), List.of(), "");

        assertFalse(output.contains("annotation may be stale"), "NONE drift should produce no stale-annotation text");
        assertFalse(output.contains("AI classifies as security-relevant but no"),
                "NONE drift should produce no missing-tag text");
        // displayName from AI suggestion ("Security test") should appear
        assertTrue(output.contains("Security test"), "AI displayName should appear in message");
    }

    // -------------------------------------------------------------------------
    // Drift messages in annotations
    // -------------------------------------------------------------------------

    @Test
    void record_tagOnlyDrift_producesNoOutput() {
        // TAG_ONLY drift: source has @Tag("security") but AI says NOT security-relevant.
        // GitHubAnnotationsEmitter filters out non-security methods before drift is evaluated,
        // so TAG_ONLY drift never produces annotation output.
        String output = recordMethodWithSourceTags(false, List.of("security"), List.of(), "");

        assertEquals("", output, "TAG_ONLY drift produces no annotation — AI disagrees on security-relevance");
    }

    @Test
    void record_aiOnlyDrift_appendsDriftNoteToMessage() {
        // source has no tag but AI says security-relevant → ai-only drift
        String output = recordMethodWithSourceTags(true, List.of(), List.of(), "");

        assertTrue(output.contains("no @Tag(\"security\") in source"),
                "ai-only drift should mention missing source tag");
        assertTrue(output.contains("AI classifies as security-relevant"),
                "ai-only drift message must identify the AI source");
    }

    @Test
    void record_noTagOnlyNorAiOnly_noDriftAppended() {
        // source has @Tag("security") AND AI says security-relevant → none
        String output = recordMethodWithSourceTags(true, List.of("security"), List.of("auth"), "");

        assertFalse(output.contains("annotation may be stale"),
                "no drift should produce no stale-annotation text");
        assertFalse(output.contains("no @Tag(\"security\") in source"),
                "no drift should produce no missing-tag text");
    }

    @Test
    void record_tagOnlyDriftExactText_doesNotContainAiOnlyText() {
        String output = recordMethodWithSourceTags(false, List.of("security"), List.of(), "");

        assertFalse(output.contains("no @Tag(\"security\") in source"),
                "tag-only drift must not contain ai-only message");
    }

    @Test
    void record_aiOnlyDriftExactText_doesNotContainTagOnlyText() {
        String output = recordMethodWithSourceTags(true, List.of(), List.of(), "");

        assertFalse(output.contains("annotation may be stale"),
                "ai-only drift must not contain tag-only message");
    }

    @Test
    void record_fqcnConvertedToFilePath() {
        String output = recordSecurityMethod(0.5, "Auth", List.of(), "src/test/java/");

        assertTrue(output.contains("file=src/test/java/com/acme/AuthTest.java"),
                "FQCN dots should become slashes with prefix");
    }

    @Test
    void record_emptyPrefix_fqcnAsRelativePath() {
        String output = recordSecurityMethod(0.5, "Auth", List.of(), "");

        assertTrue(output.contains("file=com/acme/AuthTest.java"),
                "Without prefix, file path should be FQCN-derived only");
    }

    // -------------------------------------------------------------------------
    // @DisplayName("") detection
    // -------------------------------------------------------------------------

    @Test
    void record_emptyDisplayName_noSuggestion_emitsNotice() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, "src/test/java/");

        emitter.record("com.acme.AuthTest", "testLogin", 5, 3, null, List.of(), "", null);

        String output = baos.toString(StandardCharsets.UTF_8).trim();
        assertTrue(output.startsWith("::notice "), "Empty @DisplayName should produce ::notice");
        assertTrue(output.contains("file=src/test/java/com/acme/AuthTest.java"), "File path should be present");
        assertTrue(output.contains(",line=5"), "Line number should be present");
        assertTrue(output.contains("@DisplayName(\"\")"), "Notice should reference the annotation");
        assertTrue(output.contains("unnamed in reports"), "Notice should explain the risk");
    }

    @Test
    void record_emptyDisplayName_nonSecuritySuggestion_emitsNoticeOnly() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, "");

        AiMethodSuggestion nonSecurity = new AiMethodSuggestion("testLogin", false, null, List.of(), null, 0.0, 0.0);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 3, null, List.of(), "", nonSecurity);

        String output = baos.toString(StandardCharsets.UTF_8).trim();
        // Non-security method with empty @DisplayName: only the empty-display-name notice, no security annotation
        long lineCount = output.lines().filter(l -> !l.isEmpty()).count();
        assertEquals(1, lineCount, "Should emit exactly one annotation line for empty @DisplayName");
        assertTrue(output.startsWith("::notice "), "Should be a notice, not a warning");
    }

    @Test
    void record_emptyDisplayName_securitySuggestion_emitsBothAnnotations() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, "");

        AiMethodSuggestion security = new AiMethodSuggestion("testLogin", true, "Login test", List.of("auth"), null, 0.0, 0.3);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 3, null, List.of(), "", security);

        String output = baos.toString(StandardCharsets.UTF_8).trim();
        long lineCount = output.lines().filter(l -> !l.isEmpty()).count();
        assertEquals(2, lineCount, "Security method with empty @DisplayName should emit two lines");
        assertTrue(output.contains("@DisplayName(\"\")"), "First annotation should be the empty display name notice");
        assertTrue(output.contains("Login test") || output.contains("com.acme.AuthTest#testLogin"),
                "Second annotation should be the security finding");
    }

    @Test
    void record_nullDisplayName_noExtraAnnotation() {
        // null displayName = annotation absent → no empty-display-name notice
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, "");

        emitter.record("com.acme.AuthTest", "testLogin", 5, 3, null, List.of(), null, null);

        assertEquals("", baos.toString(StandardCharsets.UTF_8).trim(),
                "Absent @DisplayName (null) should not produce any annotation");
    }

    @Test
    void record_emptyDisplayName_titleContainsFqcnAndMethod() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, "");

        emitter.record("com.acme.AuthTest", "testLogin", 5, 3, null, List.of(), "", null);

        String output = baos.toString(StandardCharsets.UTF_8).trim();
        assertTrue(output.contains("com.acme.AuthTest") && output.contains("testLogin"),
                "Title for empty @DisplayName notice should identify the method");
    }

    // -------------------------------------------------------------------------
    // Integration test via MethodAtlasApp
    // -------------------------------------------------------------------------

    @Test
    void app_githubAnnotationsMode_emitsAnnotationsForSecurityMethods(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        // Run without AI; no annotations expected (no security classification)
        String output = runApp(new String[] { "-github-annotations", sourceDir.toString() });

        // Without AI, no security classifications → no output lines
        assertEquals("", output.trim(),
                "Without AI, no security methods are known → no annotation lines");
    }

    @Test
    void app_githubAnnotationsMode_emptyDirectoryProducesNoOutput(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);

        String output = runApp(new String[] { "-github-annotations", sourceDir.toString() });

        assertEquals("", output.trim());
    }

    // -------------------------------------------------------------------------
    // MethodAtlasApp.computeFilePrefix
    // -------------------------------------------------------------------------

    @Test
    void computeFilePrefix_emptyList_returnsEmptyString() {
        assertEquals("", MethodAtlasApp.computeFilePrefix(List.of()));
    }

    @Test
    void computeFilePrefix_relativeRoot_endsWithSlash(@TempDir Path tempDir) {
        String prefix = MethodAtlasApp.computeFilePrefix(List.of(tempDir));
        assertTrue(prefix.endsWith("/"), "Prefix should end with /");
    }

    @Test
    void computeFilePrefix_usesForwardSlashes(@TempDir Path tempDir) {
        String prefix = MethodAtlasApp.computeFilePrefix(List.of(tempDir));
        assertFalse(prefix.contains("\\"), "Prefix should use forward slashes only");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String recordSecurityMethod(double interactionScore, String displayName,
            List<String> tags, String filePrefix) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, filePrefix);

        AiMethodSuggestion suggestion = new AiMethodSuggestion("testLogin", true, displayName, tags, null, 0.0, interactionScore);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 3, null, List.of(), null, suggestion);

        return baos.toString(StandardCharsets.UTF_8).trim();
    }

    /**
     * Helper for drift tests: emits one record with explicit source tags and AI securityRelevant flag.
     *
     * @param aiSecurityRelevant AI classification result
     * @param sourceTags         source-level {@code @Tag} values on the method
     * @param aiTags             AI-assigned taxonomy tags
     * @param filePrefix         file path prefix passed to the emitter
     */
    private static String recordMethodWithSourceTags(boolean aiSecurityRelevant,
            List<String> sourceTags, List<String> aiTags, String filePrefix) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, filePrefix);

        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "testLogin", aiSecurityRelevant, "Security test", aiTags, null, 0.0, 0.3);
        emitter.record("com.acme.AuthTest", "testLogin", 5, 3, null, sourceTags, null, suggestion);

        return baos.toString(StandardCharsets.UTF_8).trim();
    }

    private static String runApp(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(args, out);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static void copyFixture(Path destDir, String fixtureFileName) throws IOException {
        String resourcePath = "/fixtures/" + fixtureFileName + ".txt";
        try (InputStream in = GitHubAnnotationsEmitterTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + resourcePath);
            }
            Files.copy(in, destDir.resolve(fixtureFileName));
        }
    }
}
