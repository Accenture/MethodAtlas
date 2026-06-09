// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.egothor.methodatlas.AiCacheEntry;
import org.egothor.methodatlas.AiCacheStore;
import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.CredentialTriageVerdict;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.ai.PromptTemplateSet;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.emit.TestMethodSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for the unified-cache behaviour of
 * {@link ScanOrchestrator#runDiscovery}, driven through injected fakes (a stub
 * discovery provider and a call-counting AI engine) so the cache hit/miss,
 * signature-gating, and serve-or-triage branches are exercised directly.
 */
class ScanOrchestratorCacheTest {

    private static final String FQCN = "com.acme.LoginTest";
    private static final String METHOD = "testLogin";
    private static final String SOURCE = "class LoginTest { @Test void testLogin() {} }";

    private final String contentHash = ContentHasher.hashClass(SOURCE);
    private final String defaultSignature = PromptTemplateSet.defaults().signature();

    // ── cache hit with cached verdicts ───────────────────────────────────────

    @Test
    void cacheHit_withMatchingSignatureAndVerdicts_servesBothWithoutAnyEngineCall(
            @TempDir Path tempDir) throws IOException {
        AiClassSuggestion cached = suggestion(List.of(verdict(0, 0.9)));
        AiResultCache cache = cacheOf(tempDir, new AiCacheEntry(contentHash, defaultSignature, cached));
        CountingEngine engine = new CountingEngine();
        CredentialTriageContext ctx = contextWithOneCandidate();
        CapturingSink sink = new CapturingSink();

        run(engine, sink, cache, ctx);

        assertEquals(0, engine.classifyCalls, "classification must be served from cache, not the model");
        assertEquals(0, engine.triageCalls, "verdicts must be served from cache, not re-triaged");
        assertEquals(List.of(0.9),
                scores(ctx.verdictsByFqcn().get(FQCN)),
                "the cached verdict must be recorded for the class");
        assertNotNull(sink.lastSuggestion, "the cached classification must reach the sink");
        assertTrue(sink.lastSuggestion.securityRelevant());
    }

    // ── cache hit whose entry has no verdicts (classification-only / legacy) ──

    @Test
    void cacheHit_withoutVerdicts_issuesExactlyOneDedicatedTriageCall(@TempDir Path tempDir)
            throws IOException {
        AiClassSuggestion classificationOnly = suggestion(null);
        AiResultCache cache =
                cacheOf(tempDir, new AiCacheEntry(contentHash, defaultSignature, classificationOnly));
        CountingEngine engine = new CountingEngine();
        CredentialTriageContext ctx = contextWithOneCandidate();

        run(engine, new CapturingSink(), cache, ctx);

        assertEquals(0, engine.classifyCalls, "classification is still served from cache");
        assertEquals(1, engine.triageCalls, "a cached class lacking verdicts must be triaged exactly once");
        assertEquals(List.of(0.42), scores(ctx.verdictsByFqcn().get(FQCN)),
                "the freshly triaged verdict must be recorded");
    }

    // ── cache miss ───────────────────────────────────────────────────────────

    @Test
    void cacheMiss_runsCombinedClassificationAndRecordsVerdicts(@TempDir Path tempDir)
            throws IOException {
        CountingEngine engine = new CountingEngine();
        CredentialTriageContext ctx = contextWithOneCandidate();

        run(engine, new CapturingSink(), AiResultCache.empty(), ctx);

        assertEquals(1, engine.classifyWithCandidatesCalls,
                "a miss must run the combined classification+triage call once");
        assertEquals(0, engine.triageCalls, "the combined call carries triage; no separate triage call");
        assertEquals(List.of(0.77), scores(ctx.verdictsByFqcn().get(FQCN)),
                "verdicts from the combined call must be recorded");
    }

    // ── signature mismatch is a miss ─────────────────────────────────────────

    @Test
    void cacheEntryFromDifferentPromptCatalogue_isNotReused(@TempDir Path tempDir) throws IOException {
        AiClassSuggestion cached = suggestion(List.of(verdict(0, 0.9)));
        AiResultCache cache =
                cacheOf(tempDir, new AiCacheEntry(contentHash, "a-different-signature", cached));
        CountingEngine engine = new CountingEngine();

        run(engine, new CapturingSink(), cache, contextWithOneCandidate());

        assertEquals(1, engine.classifyWithCandidatesCalls,
                "an answer from a different prompt catalogue must not be reused; the model is re-queried");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void run(AiSuggestionEngine engine, TestMethodSink sink, AiResultCache cache,
            CredentialTriageContext ctx) throws IOException {
        ScanOrchestrator orchestrator = new ScanOrchestrator(new PluginLoader());
        AiOptions options = AiOptions.builder().enabled(true).build();
        orchestrator.runDiscovery(Path.of("."), List.of(new OneClassProvider()), options, engine,
                sink, /* contentHashEnabled */ true, ClassificationOverride.empty(), cache, ctx);
    }

    private static AiResultCache cacheOf(Path dir, AiCacheEntry entry) throws IOException {
        Path file = dir.resolve("cache.jsonl");
        AiCacheStore.write(file, List.of(entry));
        return AiResultCache.load(file);
    }

    private static CredentialTriageContext contextWithOneCandidate() {
        return new CredentialTriageContext(Map.of(FQCN,
                List.of(new PromptBuilder.CredentialCandidateRef(0, 12, "AKIAEXAMPLE"))));
    }

    private static AiClassSuggestion suggestion(List<CredentialTriageVerdict> secrets) {
        AiMethodSuggestion method = new AiMethodSuggestion(METHOD, true, "SECURITY: auth",
                List.of("security", "auth"), "Tests login.", 0.9, 0.0);
        return new AiClassSuggestion(FQCN, Boolean.TRUE, List.of("security"), "reason",
                List.of(method), secrets);
    }

    private static CredentialTriageVerdict verdict(int index, double score) {
        return new CredentialTriageVerdict(index, score, "https://api.example", "rationale");
    }

    private static List<Double> scores(List<CredentialTriageVerdict> verdicts) {
        assertNotNull(verdicts, "verdicts must have been recorded");
        return verdicts.stream().map(CredentialTriageVerdict::credibilityScore).toList();
    }

    /** A stub provider yielding exactly one class with source content. */
    private static final class OneClassProvider implements TestDiscovery {
        @Override
        public String pluginId() {
            return "fake";
        }

        @Override
        public Stream<DiscoveredMethod> discover(Path root) {
            SourceContent source = () -> Optional.of(SOURCE);
            return Stream.of(new DiscoveredMethod(FQCN, METHOD, 1, 1, 0, List.of(), null,
                    null, "LoginTest", source));
        }

        @Override
        public boolean hadErrors() {
            return false;
        }
    }

    /** An AI engine that counts the calls each branch makes. */
    private static final class CountingEngine implements AiSuggestionEngine {
        private int classifyCalls;
        private int classifyWithCandidatesCalls;
        private int triageCalls;

        @Override
        public AiClassSuggestion suggestForClass(String fileStem, String fqcn, String classSource,
                List<PromptBuilder.TargetMethod> targetMethods) {
            classifyCalls++;
            return suggestion(null);
        }

        @Override
        public AiClassSuggestion suggestForClass(String fileStem, String fqcn, String classSource,
                List<PromptBuilder.TargetMethod> targetMethods,
                List<PromptBuilder.CredentialCandidateRef> candidates) {
            classifyWithCandidatesCalls++;
            return suggestion(List.of(verdict(0, 0.77)));
        }

        @Override
        public List<CredentialTriageVerdict> triageSecrets(String fqcn, String classSource,
                List<PromptBuilder.CredentialCandidateRef> candidates) {
            triageCalls++;
            return List.of(verdict(0, 0.42));
        }
    }

    /** A sink that remembers the last AI suggestion it received. */
    private static final class CapturingSink implements TestMethodSink {
        private AiMethodSuggestion lastSuggestion;
        private final List<String> methods = new ArrayList<>();

        @Override
        public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
                List<String> tags, String displayName, AiMethodSuggestion suggestion) {
            methods.add(method);
            if (suggestion != null) {
                this.lastSuggestion = suggestion;
            }
        }
    }
}
