// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.emit.TestMethodSink;
import org.junit.jupiter.api.Test;

/**
 * Tests the fail-closed comment-redaction wiring of {@link ScanOrchestrator}
 * with a stub {@link CommentRedactor} and a call-recording AI engine.
 */
class ScanOrchestratorRedactionTest {

    private static final String COMMENT = "// secret-note";
    private static final String SOURCE = "class A { " + COMMENT + "\n  void t() {}\n}";
    private static final String REDACTED = "class A { " + " ".repeat(COMMENT.length())
            + "\n  void t() {}\n}";

    @Test
    void redactionOff_engineReceivesOriginalSourceAndHashIsOfOriginal() throws IOException {
        RecordingEngine engine = new RecordingEngine();
        RecordingSink sink = new RecordingSink();

        run(CommentRedaction.none(), AiOptions.builder().enabled(true).build(), engine, sink,
                provider("com.x.A", Path.of("A.stub")));

        assertEquals(List.of(SOURCE), engine.payloads);
        assertEquals(ContentHasher.hashClass(SOURCE), sink.hashes.get(0));
    }

    @Test
    void noRedactorForLanguage_abortsWithZeroProviderCalls() {
        RecordingEngine engine = new RecordingEngine();

        RedactionAbortedException ex = assertThrows(RedactionAbortedException.class,
                () -> run(CommentRedaction.none(), redactOptions(), engine, new RecordingSink(),
                        provider("com.x.A", Path.of("A.stub"))));

        assertEquals(0, engine.payloads.size(), "no provider call may precede the abort");
        assertTrue(ex.getMessage().contains("A.stub") && ex.getMessage().contains("*.stub"), ex.getMessage());
    }

    @Test
    void oneUnsupportedFileAmongMany_abortsBeforeAnyProviderCall() {
        RecordingEngine engine = new RecordingEngine();
        CommentRedaction redaction = new CommentRedaction(List.of(new StubRedactor(".stub")));

        assertThrows(RedactionAbortedException.class,
                () -> run(redaction, redactOptions(), engine, new RecordingSink(),
                        provider("com.x.A", Path.of("A.stub")), provider("com.x.B", Path.of("B.other"))));

        assertEquals(0, engine.payloads.size(), "the pre-pass must reject B before A is sent");
    }

    @Test
    void redactorFailure_abortsWithZeroProviderCalls() {
        RecordingEngine engine = new RecordingEngine();
        CommentRedaction redaction = new CommentRedaction(List.of(new FailingRedactor()));

        RedactionAbortedException ex = assertThrows(RedactionAbortedException.class,
                () -> run(redaction, redactOptions(), engine, new RecordingSink(),
                        provider("com.x.A", Path.of("A.stub"))));

        assertEquals(0, engine.payloads.size());
        assertTrue(ex.getMessage().contains("unterminated"), ex.getMessage());
    }

    @Test
    void missingFilePath_abortsFailClosed() {
        RecordingEngine engine = new RecordingEngine();
        CommentRedaction redaction = new CommentRedaction(List.of(new StubRedactor(".stub")));

        assertThrows(RedactionAbortedException.class,
                () -> run(redaction, redactOptions(), engine, new RecordingSink(), provider("com.x.A", null)));

        assertEquals(0, engine.payloads.size());
    }

    @Test
    void stubRedactor_engineReceivesRedactedSourceAndHashIsOfRedactedText() throws IOException {
        RecordingEngine engine = new RecordingEngine();
        RecordingSink sink = new RecordingSink();
        CommentRedaction redaction = new CommentRedaction(List.of(new StubRedactor(".stub")));

        run(redaction, redactOptions(), engine, sink, provider("com.x.A", Path.of("A.stub")));

        assertEquals(List.of(REDACTED), engine.payloads);
        assertEquals(SOURCE.length(), engine.payloads.get(0).length(), "length must be preserved");
        assertEquals(ContentHasher.hashClass(REDACTED), sink.hashes.get(0));
    }

    @Test
    void redactionRequestedButAiInactive_isNoOp() throws IOException {
        RecordingSink sink = new RecordingSink();

        run(CommentRedaction.none(), redactOptions(), null, sink, provider("com.x.A", Path.of("A.stub")));

        assertEquals(1, sink.hashes.size(), "a scan without an AI engine sends nothing and must not abort");
    }

    @Test
    void gatherAiSuggestions_sendsRedactedSource() {
        Map<String, List<DiscoveredMethod>> byClass = Map.of("com.x.A",
                List.of(method("com.x.A", Path.of("A.stub"))));
        RecordingEngine engine = new RecordingEngine();
        AiRuntime ai = new AiRuntime(redactOptions(), engine, ClassificationOverride.empty(),
                AiResultCache.empty());

        new ScanOrchestrator(new PluginLoader(), Optional.empty(), false,
                new CommentRedaction(List.of(new StubRedactor(".stub"))))
                .gatherAiSuggestionsForFile(byClass, ai, AiResultCache.empty(), new HashMap<>(),
                        new HashMap<>());

        assertEquals(List.of(REDACTED), engine.payloads);
    }

    @Test
    void gatherAiSuggestions_unsupportedLanguage_abortsWithZeroProviderCalls() {
        Map<String, List<DiscoveredMethod>> byClass = Map.of("com.x.A",
                List.of(method("com.x.A", Path.of("A.stub"))));
        RecordingEngine engine = new RecordingEngine();
        AiRuntime ai = new AiRuntime(redactOptions(), engine, ClassificationOverride.empty(),
                AiResultCache.empty());
        ScanOrchestrator orchestrator = new ScanOrchestrator(new PluginLoader());

        assertThrows(RedactionAbortedException.class, () -> orchestrator.gatherAiSuggestionsForFile(byClass, ai,
                AiResultCache.empty(), new HashMap<>(), new HashMap<>()));

        assertEquals(0, engine.payloads.size());
    }

    @Test
    void requireRedactable_rejectsUnsupportedFileUpFront() {
        AiRuntime ai = new AiRuntime(redactOptions(), new RecordingEngine(), ClassificationOverride.empty(),
                AiResultCache.empty());
        ScanOrchestrator orchestrator = new ScanOrchestrator(new PluginLoader(), Optional.empty(), false,
                new CommentRedaction(List.of(new StubRedactor(".stub"))));

        orchestrator.requireRedactable(List.of(List.of(method("com.x.A", Path.of("A.stub")))), ai);

        assertThrows(RedactionAbortedException.class, () -> orchestrator
                .requireRedactable(List.of(List.of(method("com.x.B", Path.of("B.other")))), ai));
    }

    // -- helpers ---------------------------------------------------------------

    private static AiOptions redactOptions() {
        return AiOptions.builder().enabled(true).redactComments(true).build();
    }

    private static void run(CommentRedaction redaction, AiOptions options, AiSuggestionEngine engine,
            TestMethodSink sink, TestDiscovery... providers) throws IOException {
        new ScanOrchestrator(new PluginLoader(), Optional.empty(), false, redaction).runDiscovery(Path.of("."),
                List.of(providers), options, engine, sink, true, ClassificationOverride.empty(),
                AiResultCache.empty(), null);
    }

    private static DiscoveredMethod method(String fqcn, Path file) {
        SourceContent source = () -> Optional.of(SOURCE);
        return new DiscoveredMethod(fqcn, "t", 1, 1, 1, List.of(), null, file, "A", source);
    }

    private static TestDiscovery provider(String fqcn, Path file) {
        return new TestDiscovery() {
            @Override
            public String pluginId() {
                return "fake-" + fqcn;
            }

            @Override
            public Stream<DiscoveredMethod> discover(Path root) {
                return Stream.of(method(fqcn, file));
            }

            @Override
            public boolean hadErrors() {
                return false;
            }
        };
    }

    /** Blanks line comments to end of line; sufficient for the test fixtures only. */
    private static final class StubRedactor implements CommentRedactor {
        private final String suffix;

        StubRedactor(String suffix) {
            this.suffix = suffix;
        }

        @Override
        public String pluginId() {
            return "stub";
        }

        @Override
        public boolean supports(Path sourceFile) {
            return sourceFile.toString().endsWith(suffix);
        }

        @Override
        public String redact(Path sourceFile, String source) {
            StringBuilder out = new StringBuilder(source.length());
            boolean inComment = false;
            for (int i = 0; i < source.length(); i++) {
                char c = source.charAt(i);
                if (!inComment && c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    inComment = true;
                }
                if (c == '\n') {
                    inComment = false;
                }
                out.append(inComment ? ' ' : c);
            }
            return out.toString();
        }
    }

    private static final class FailingRedactor implements CommentRedactor {
        @Override
        public String pluginId() {
            return "failing";
        }

        @Override
        public boolean supports(Path sourceFile) {
            return true;
        }

        @Override
        public String redact(Path sourceFile, String source) throws CommentRedactionException {
            throw new CommentRedactionException("unterminated block comment");
        }
    }

    private static final class RecordingEngine implements AiSuggestionEngine {
        private final List<String> payloads = new ArrayList<>();

        @Override
        public AiClassSuggestion suggestForClass(String fileStem, String fqcn, String classSource,
                List<PromptBuilder.TargetMethod> targetMethods) {
            payloads.add(classSource);
            return new AiClassSuggestion(fqcn, Boolean.FALSE, List.of(), "r",
                    List.of(new AiMethodSuggestion("t", false, null, List.of(), null, 0.0, 0.0)), null);
        }
    }

    private static final class RecordingSink implements TestMethodSink {
        private final List<String> hashes = new ArrayList<>();

        @Override
        public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
                List<String> tags, String displayName, AiMethodSuggestion suggestion) {
            hashes.add(contentHash);
        }
    }
}
