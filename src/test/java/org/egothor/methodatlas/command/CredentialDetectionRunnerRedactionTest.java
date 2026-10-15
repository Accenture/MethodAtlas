// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.CredentialTriageVerdict;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.api.CredentialCandidate;
import org.egothor.methodatlas.api.CredentialCategory;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.emit.TestMethodSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that credential triage honours {@code -redact-comments}: the AI engine never
 * receives comment text or a comment-resident secret, an unredactable file aborts the
 * run before any provider call, and the local report still lists every secret.
 */
class CredentialDetectionRunnerRedactionTest {

    private static final String COMMENT_KEY = "AKIAQQQQWWWWEEEERRRR";
    private static final String CODE_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String COMMENT_PROSE = "ROTATE-ME-JANE-DOE";

    private static final String SOURCE = String.join("\n",
            "package com.example;",
            "",
            "import org.junit.jupiter.api.Test;",
            "",
            "public class CredTest {",
            "    // " + COMMENT_PROSE + " old key " + COMMENT_KEY,
            "    @Test",
            "    void testLogin() {",
            "        String k = \"" + CODE_KEY + "\";",
            "    }",
            "}",
            "");

    private static final String ABAP_SOURCE = String.join(System.lineSeparator(),
            "CLASS zcl_auth_test DEFINITION PUBLIC FINAL FOR TESTING RISK LEVEL HARMLESS DURATION SHORT.",
            "  PUBLIC SECTION.",
            "    METHODS test_login FOR TESTING.",
            "ENDCLASS.",
            "CLASS zcl_auth_test IMPLEMENTATION.",
            "  METHOD test_login.",
            "    DATA lv_result TYPE abap_bool.",
            "  ENDMETHOD.",
            "ENDCLASS.",
            "");

    @TempDir
    Path tempDir;

    @Test
    void separatePath_redactsSourceAndWithholdsCommentSecretFromAi() throws IOException {
        Path root = writeSource("CredTest.java", SOURCE);
        CliConfig cfg = config(root, true, null, true);
        RecordingEngine engine = new RecordingEngine();

        runner(cfg, engine).run(List.of(root), null);

        assertEquals(1, engine.triageSources.size(), "one triage call for the one file");
        assertAiSawNeitherComment(engine);
        assertEquals(List.of(CODE_KEY), engine.triageSnippets);
        assertReportListsBoth();
    }

    @Test
    void separatePath_withoutRedaction_sendsEverythingAsBefore() throws IOException {
        Path root = writeSource("CredTest.java", SOURCE);
        CliConfig cfg = config(root, false, null, true);
        RecordingEngine engine = new RecordingEngine();

        runner(cfg, engine).run(List.of(root), null);

        assertEquals(List.of(SOURCE), engine.triageSources);
        assertTrue(engine.triageSnippets.contains(COMMENT_KEY));
        assertTrue(engine.triageSnippets.contains(CODE_KEY));
    }

    @Test
    void separatePath_unredactableFileAbortsBeforeAnyProviderCall() throws IOException {
        Path root = writeSource("CredTest.java", SOURCE);
        Files.writeString(root.resolve("notes.txt"), "token " + CODE_KEY + "\n");
        CliConfig cfg = config(root, true, "**/*.*", false);
        RecordingEngine engine = new RecordingEngine();

        assertThrows(RedactionAbortedException.class, () -> runner(cfg, engine).run(List.of(root), null));

        assertEquals(0, engine.calls(), "the pre-flight must reject notes.txt before the Java file is sent");
        assertFalse(Files.exists(tempDir.resolve("creds.csv")), "an aborted run writes no report");
    }

    @Test
    void foldedPath_redactsClassSourceAndWithholdsCommentSecretFromAi() throws IOException {
        Path root = writeSource("CredTest.java", SOURCE);
        CliConfig cfg = config(root, true, null, false);
        RecordingEngine engine = new RecordingEngine();
        CredentialDetectionRunner runner = runner(cfg, engine);

        CredentialTriageContext ctx = runner.prepare(List.of(root));
        scan(root, cfg, engine, runner, ctx);
        runner.finish(List.of(root), null);

        assertEquals(1, engine.classSources.size());
        assertEquals(0, engine.triageSources.size(), "folded triage uses the classification call only");
        assertFalse(engine.classSources.get(0).contains(COMMENT_PROSE));
        assertFalse(engine.classSources.get(0).contains(COMMENT_KEY));
        assertEquals(List.of(CODE_KEY), engine.classSnippets);
        assertReportListsBoth();
    }

    @Test
    void foldedPath_unredactableFileAbortsBeforeAnyProviderCall() throws IOException {
        Path root = writeSource("CredTest.java", SOURCE);
        Files.writeString(root.resolve("zcl_auth_test.abap"), ABAP_SOURCE);
        CliConfig cfg = config(root, true, null, false);
        RecordingEngine engine = new RecordingEngine();

        assertThrows(RedactionAbortedException.class, () -> {
            CredentialDetectionRunner runner = runner(cfg, engine);
            CredentialTriageContext ctx = runner.prepare(List.of(root));
            scan(root, cfg, engine, runner, ctx);
        });

        assertEquals(0, engine.calls());
    }

    @Test
    void secretsOnlyInComments_noTriageCallIsMadeAndReportStillListsThem() throws IOException {
        Path root = writeSource("CredTest.java", SOURCE.replace("\"" + CODE_KEY + "\"", "\"plain\""));
        CliConfig cfg = config(root, true, null, true);
        RecordingEngine engine = new RecordingEngine();

        runner(cfg, engine).run(List.of(root), null);

        assertEquals(0, engine.calls(), "nothing but a comment-resident candidate: nothing to triage");
        String csv = readReport();
        assertTrue(csv.contains(COMMENT_KEY), csv);
    }

    // -- overlapsBlankedText ------------------------------------------------------

    @Test
    void overlapsBlankedText_detectsCandidateInsideBlankedRange() {
        String original = "int a; // key AKIAQQQQWWWWEEEERRRR\nint b;\n";
        String redacted = "int a; " + " ".repeat("// key AKIAQQQQWWWWEEEERRRR".length()) + "\nint b;\n";

        assertTrue(CredentialDetectionRunner.overlapsBlankedText(original, redacted,
                candidate(1, original.indexOf("AKIA") + 1, 1, original.indexOf("AKIA") + 21)));
        assertFalse(CredentialDetectionRunner.overlapsBlankedText(original, redacted, candidate(2, 1, 2, 6)));
    }

    @Test
    void overlapsBlankedText_handlesMultiLineSpansAndLineEndBoundary() {
        String original = "x = 1\n/* a\nb */ y\nz\n";
        String redacted = "x = 1\n" + " ".repeat(4) + "\n" + " ".repeat(4) + " y\nz\n";

        assertEquals(original.length(), redacted.length());
        // span covering "/* a\nb */" across two lines
        assertTrue(CredentialDetectionRunner.overlapsBlankedText(original, redacted, candidate(2, 1, 3, 5)));
        // span "x = 1\n" ending exactly at a line break: the detector reports end line 1, end column 1
        assertFalse(CredentialDetectionRunner.overlapsBlankedText(original, redacted, candidate(1, 1, 1, 1)));
        // span "y" after the comment on line 3
        assertFalse(CredentialDetectionRunner.overlapsBlankedText(original, redacted, candidate(3, 6, 3, 7)));
    }

    @Test
    void overlapsBlankedText_failsClosedOnUnmappableSpanOrLengthMismatch() {
        assertTrue(CredentialDetectionRunner.overlapsBlankedText("a\n", "a\n", candidate(9, 1, 9, 2)));
        assertTrue(CredentialDetectionRunner.overlapsBlankedText("abc", "ab", candidate(1, 1, 1, 2)));
    }

    // -- helpers ------------------------------------------------------------------

    private void assertAiSawNeitherComment(RecordingEngine engine) {
        String sent = engine.triageSources.get(0);
        assertFalse(sent.contains(COMMENT_PROSE), "comment prose must not reach the AI");
        assertFalse(sent.contains(COMMENT_KEY), "comment-resident secret must not reach the AI");
        assertTrue(sent.contains(CODE_KEY), "code is still sent");
        assertEquals(SOURCE.length(), sent.length(), "redaction preserves length");
    }

    private void assertReportListsBoth() throws IOException {
        String csv = readReport();
        assertTrue(csv.contains(COMMENT_KEY), "local report must list the comment secret: " + csv);
        assertTrue(csv.contains(CODE_KEY), "local report must list the code secret: " + csv);
    }

    private String readReport() throws IOException {
        return Files.readString(tempDir.resolve("creds.csv"));
    }

    private Path writeSource(String name, String content) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(root.resolve(name), content);
        return root;
    }

    /**
     * Builds a configuration. {@code CliArgs} is package-private to another package, so the
     * accessors the runner and orchestrator read are stubbed on a mock of the record.
     */
    private CliConfig config(Path root, boolean redact, String include, boolean separate) {
        CliConfig cfg = mock(CliConfig.class);
        when(cfg.aiOptions()).thenReturn(AiOptions.builder().enabled(true).redactComments(redact).build());
        when(cfg.paths()).thenReturn(List.of(root));
        when(cfg.fileSuffixes()).thenReturn(List.of("Test.java", ".abap"));
        when(cfg.testMarkers()).thenReturn(Set.of());
        when(cfg.properties()).thenReturn(Map.of());
        when(cfg.detectSecrets()).thenReturn(true);
        when(cfg.secretsInclude()).thenReturn(include);
        when(cfg.secretsSeparateLlm()).thenReturn(separate);
        when(cfg.secretsShowValues()).thenReturn(true);
        when(cfg.secretsMinScore()).thenReturn(0.0);
        when(cfg.secretsOut()).thenReturn(tempDir.resolve("creds.csv"));
        return cfg;
    }

    private static CredentialDetectionRunner runner(CliConfig cfg, AiSuggestionEngine engine) {
        PluginLoader loader = new PluginLoader();
        TestDiscoveryConfig dc = discoveryConfig(cfg);
        CommentRedaction redaction = cfg.aiOptions().redactComments()
                ? new CommentRedaction(loader.loadRedactors(dc))
                : CommentRedaction.none();
        return new CredentialDetectionRunner(cfg, dc, loader,
                new ScanOrchestrator(loader, Optional.empty(), false, redaction), engine);
    }

    private static TestDiscoveryConfig discoveryConfig(CliConfig cfg) {
        return new TestDiscoveryConfig(cfg.fileSuffixes(), cfg.testMarkers(), cfg.properties());
    }

    private static void scan(Path root, CliConfig cfg, AiSuggestionEngine engine,
            CredentialDetectionRunner runner, CredentialTriageContext ctx) throws IOException {
        PluginLoader loader = new PluginLoader();
        TestDiscoveryConfig dc = discoveryConfig(cfg);
        CommentRedaction redaction = new CommentRedaction(loader.loadRedactors(dc));
        List<TestDiscovery> providers = loader.loadProviders(dc);
        try {
            new ScanOrchestrator(loader, Optional.empty(), false, redaction).runDiscovery(root, providers,
                    cfg.aiOptions(), engine, new NoopSink(), false, ClassificationOverride.empty(),
                    AiResultCache.empty(), ctx);
        } finally {
            loader.closeAll(providers);
        }
    }

    private static CredentialCandidate candidate(int beginLine, int beginColumn, int endLine, int endColumn) {
        return new CredentialCandidate("stub", "r", CredentialCategory.PROVIDER_TOKEN, beginLine, beginColumn,
                endLine, endColumn, "v");
    }

    private static final class NoopSink implements TestMethodSink {
        @Override
        public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
                List<String> tags, String displayName, AiMethodSuggestion suggestion) {
            // classification output is irrelevant to these tests
        }
    }

    /** Records every payload the "provider" would have received. */
    private static final class RecordingEngine implements AiSuggestionEngine {
        private final List<String> classSources = new ArrayList<>();
        private final List<String> classSnippets = new ArrayList<>();
        private final List<String> triageSources = new ArrayList<>();
        private final List<String> triageSnippets = new ArrayList<>();

        int calls() {
            return classSources.size() + triageSources.size();
        }

        @Override
        public AiClassSuggestion suggestForClass(String fileStem, String fqcn, String classSource,
                List<PromptBuilder.TargetMethod> targetMethods) {
            return suggestForClass(fileStem, fqcn, classSource, targetMethods, List.of());
        }

        @Override
        public AiClassSuggestion suggestForClass(String fileStem, String fqcn, String classSource,
                List<PromptBuilder.TargetMethod> targetMethods,
                List<PromptBuilder.CredentialCandidateRef> secretCandidates) {
            classSources.add(classSource);
            secretCandidates.forEach(c -> classSnippets.add(c.snippet()));
            return new AiClassSuggestion(fqcn, Boolean.FALSE, List.of(), "r",
                    List.of(new AiMethodSuggestion("testLogin", false, null, List.of(), null, 0.0, 0.0)),
                    verdicts(secretCandidates));
        }

        @Override
        public List<CredentialTriageVerdict> triageSecrets(String fqcn, String classSource,
                List<PromptBuilder.CredentialCandidateRef> candidates) {
            triageSources.add(classSource);
            candidates.forEach(c -> triageSnippets.add(c.snippet()));
            return verdicts(candidates);
        }

        private static List<CredentialTriageVerdict> verdicts(List<PromptBuilder.CredentialCandidateRef> refs) {
            return refs.stream().map(r -> new CredentialTriageVerdict(r.index(), 0.9, null, "stub")).toList();
        }
    }
}
