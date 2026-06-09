// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.egothor.methodatlas.AiCacheEntry;
import org.egothor.methodatlas.AiCacheStore;
import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.emit.CompositeTestMethodSink;
import org.egothor.methodatlas.emit.TestMethodSink;
import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionException;
import org.egothor.methodatlas.ai.CredentialTriageVerdict;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.ai.SuggestionLookup;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * Orchestrates the scan-and-emit loop that every command mode is built around.
 *
 * <p>
 * Each {@link Command} mode varies in three places — output format, per-record
 * sink behaviour, and whether records stream or buffer — but they all share
 * the same core sequence:
 * </p>
 * <ol>
 *   <li>Load all configured {@link TestDiscovery} providers.</li>
 *   <li>For each scan root, run every provider, merge their results, and
 *       group methods by class.</li>
 *   <li>For each class, optionally consult the AI engine through a layered
 *       cache + override lookup.</li>
 *   <li>Forward each method record to the supplied sink.</li>
 *   <li>Close all providers.</li>
 * </ol>
 *
 * <p>
 * This class owns that sequence. Commands compose it with a
 * {@link PluginLoader} (passed in at construction) and configure the
 * per-record sink, AI runtime, and content-hash policy at call time.
 * </p>
 *
 * <h2>API shape</h2>
 *
 * <p>
 * Two entry points serve the two common patterns:
 * </p>
 * <ul>
 *   <li>{@link #scan} manages the provider lifecycle internally; it is the
 *       right call for SARIF, JSON, and GitHub-annotation modes that buffer
 *       or emit unconditionally.</li>
 *   <li>{@link #runDiscovery} processes a single root against pre-loaded
 *       providers; it is the right call for CSV and plain-text modes that
 *       compute per-root metadata (such as the {@code source_root} column)
 *       before forwarding records.</li>
 * </ul>
 *
 * <p>
 * The apply-tags flow has its own shape: {@link #collectMethodsByFile}
 * groups discovered methods by source file (the caller owns the provider
 * lifecycle so it can read each provider's
 * {@link TestDiscovery#hadErrors()} afterwards), and
 * {@link #gatherAiSuggestionsForFile} resolves AI suggestions for one
 * file at a time.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * This class is thread-safe. The injected {@link PluginLoader} is
 * thread-safe and {@link java.util.ServiceLoader} resolution is
 * idempotent; nothing else is shared between calls.
 * </p>
 *
 * @see PluginLoader
 * @see AiRuntime
 * @see Command
 * @since 1.0.0
 */
public final class ScanOrchestrator {

    private static final Logger LOG = Logger.getLogger(ScanOrchestrator.class.getName());

    private final PluginLoader pluginLoader;

    /**
     * Optional secondary sink that observes every record alongside the
     * command's primary sink. {@code null} means no fan-out.
     */
    private final TestMethodSink extraSink;

    /**
     * Creates a new orchestrator with no extra sink.
     *
     * @param pluginLoader plugin loader used by {@link #scan} and
     *                     {@link #collectMethodsByFile}; must not be
     *                     {@code null}
     */
    public ScanOrchestrator(PluginLoader pluginLoader) {
        this(pluginLoader, Optional.empty());
    }

    /**
     * Creates a new orchestrator that fans out every record to {@code extraSink}
     * in addition to the command's primary sink.
     *
     * <p>
     * The extra sink, when present, is composed with the primary sink at the
     * {@link #runDiscovery} boundary so every command mode (SARIF, JSON, CSV,
     * GitHub annotations) sees the same fan-out automatically. When
     * {@code extraSink} is {@link Optional#empty()} the orchestrator behaves
     * identically to the legacy single-sink constructor.
     * </p>
     *
     * @param pluginLoader plugin loader used by {@link #scan} and
     *                     {@link #collectMethodsByFile}; must not be
     *                     {@code null}
     * @param extraSink    optional secondary sink invoked in addition to the
     *                     command-supplied primary sink; must not be
     *                     {@code null} (use {@link Optional#empty()})
     */
    public ScanOrchestrator(PluginLoader pluginLoader, Optional<TestMethodSink> extraSink) {
        this.pluginLoader = pluginLoader;
        this.extraSink = extraSink.orElse(null);
    }

    /**
     * Scans every configured root, forwarding each discovered test method to
     * {@code sink}. Loads and closes the {@link TestDiscovery} providers
     * internally so callers do not need to manage the lifecycle.
     *
     * @param roots           source roots to scan; must not be {@code null}
     * @param cliConfig       full parsed CLI configuration
     * @param discoveryConfig discovery configuration forwarded to providers
     * @param aiEngine        AI engine providing suggestions; may be
     *                        {@code null} when AI is disabled
     * @param sink            receiver of discovered test method records
     * @param override        human classification overrides
     * @param aiCache         AI result cache
     * @return {@code 0} if all files were processed successfully, {@code 1}
     *         if any file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     * @since 1.0.0
     */
    public int scan(List<Path> roots, CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, TestMethodSink sink,
            ClassificationOverride override, AiResultCache aiCache) throws IOException {
        return scan(roots, cliConfig, discoveryConfig, aiEngine, sink, override, aiCache, null);
    }

    /**
     * Scans every configured root like
     * {@link #scan(List, CliConfig, TestDiscoveryConfig, AiSuggestionEngine, TestMethodSink, ClassificationOverride, AiResultCache)},
     * additionally folding credential triage into each per-class AI call when
     * {@code secretCtx} is supplied, so the class source is sent to the provider
     * once for both classification and triage.
     *
     * @param roots           source roots to scan; must not be {@code null}
     * @param cliConfig       full parsed CLI configuration
     * @param discoveryConfig discovery configuration forwarded to providers
     * @param aiEngine        AI engine providing suggestions; may be {@code null}
     * @param sink            receiver of discovered test method records
     * @param override        human classification overrides
     * @param aiCache         AI result cache
     * @param secretCtx       credential-triage context, or {@code null} to disable
     *                        the fold (classification only)
     * @return {@code 0} if all files were processed successfully, {@code 1} otherwise
     * @throws IOException if traversing a file tree fails
     * @since 4.1.0
     */
    public int scan(List<Path> roots, CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, TestMethodSink sink,
            ClassificationOverride override, AiResultCache aiCache,
            CredentialTriageContext secretCtx) throws IOException {
        List<TestDiscovery> providers = pluginLoader.loadProviders(discoveryConfig);
        boolean hadErrors = false;
        // Accumulates one cache entry per processed class across all roots; written
        // once at the end when -ai-cache-out is set.
        Map<String, AiCacheEntry> cacheAll =
                cliConfig.aiCacheOut() != null ? new LinkedHashMap<>() : null;
        try {
            for (Path root : roots) {
                DiscoveryResult result = runDiscoveryInternal(root, providers, cliConfig.aiOptions(),
                        aiEngine, sink, cliConfig.contentHash(), override, aiCache, secretCtx);
                if (result.hadErrors()) {
                    hadErrors = true;
                }
                if (cacheAll != null) {
                    cacheAll.putAll(result.cacheEntries());
                }
            }
        } finally {
            pluginLoader.closeAll(providers);
        }
        if (cacheAll != null) {
            AiCacheStore.write(cliConfig.aiCacheOut(), cacheAll.values());
        }
        return hadErrors ? 1 : 0;
    }

    /**
     * Runs all configured {@link TestDiscovery} providers on {@code root},
     * merges their results, orchestrates AI analysis per class, and forwards
     * each method record to {@code sink}.
     *
     * <p>
     * Providers are passed in pre-loaded; callers manage the lifecycle
     * (typically through {@link PluginLoader#closeAll(List)} in a
     * {@code finally} block) so that they can share one provider list across
     * multiple roots while still computing per-root metadata before each
     * call.
     * </p>
     *
     * @param root               directory to scan
     * @param providers          list of pre-configured discovery providers
     * @param aiOptions          AI configuration for the current run
     * @param aiEngine           AI engine, or {@code null} when AI is disabled
     * @param sink               receiver of discovered test method records
     * @param contentHashEnabled whether to include the class content hash in
     *                           emitted records
     * @param override           human classification overrides
     * @param aiCache            AI result cache
     * @return {@code true} if any provider encountered a parse or processing
     *         error
     * @throws IOException if traversing the file tree fails
     * @since 1.0.0
     */
    public boolean runDiscovery(Path root, List<TestDiscovery> providers,
            AiOptions aiOptions, AiSuggestionEngine aiEngine, TestMethodSink sink,
            boolean contentHashEnabled, ClassificationOverride override,
            AiResultCache aiCache) throws IOException {
        return runDiscovery(root, providers, aiOptions, aiEngine, sink, contentHashEnabled,
                override, aiCache, null);
    }

    /**
     * Runs discovery and AI analysis for one root like
     * {@link #runDiscovery(Path, List, AiOptions, AiSuggestionEngine, TestMethodSink, boolean, ClassificationOverride, AiResultCache)},
     * additionally folding credential triage into each per-class AI call when
     * {@code secretCtx} is supplied.
     *
     * @param root               directory to scan
     * @param providers          pre-configured discovery providers
     * @param aiOptions          AI configuration for the current run
     * @param aiEngine           AI engine, or {@code null} when AI is disabled
     * @param sink               receiver of discovered test method records
     * @param contentHashEnabled whether to include the class content hash
     * @param override           human classification overrides
     * @param aiCache            AI result cache
     * @param secretCtx          credential-triage context, or {@code null} to disable the fold
     * @return {@code true} if any provider encountered an error
     * @throws IOException if traversing the file tree fails
     * @since 4.1.0
     */
    public boolean runDiscovery(Path root, List<TestDiscovery> providers,
            AiOptions aiOptions, AiSuggestionEngine aiEngine, TestMethodSink sink,
            boolean contentHashEnabled, ClassificationOverride override,
            AiResultCache aiCache, CredentialTriageContext secretCtx) throws IOException {
        return runDiscoveryInternal(root, providers, aiOptions, aiEngine, sink, contentHashEnabled,
                override, aiCache, secretCtx).hadErrors();
    }

    /**
     * Body of {@link #runDiscovery(Path, List, AiOptions, AiSuggestionEngine, TestMethodSink,
     * boolean, ClassificationOverride, AiResultCache, CredentialTriageContext)} that also returns one
     * {@link AiCacheEntry} per processed class (cache hits preserved, misses freshly computed) so the
     * caller can persist the unified AI result cache.
     *
     * @return the error flag plus the per-class cache entries keyed by content hash
     */
    @SuppressWarnings("PMD.CloseResource") // providers are owned by the caller; this method does not close them
    private DiscoveryResult runDiscoveryInternal(Path root, List<TestDiscovery> providers,
            AiOptions aiOptions, AiSuggestionEngine aiEngine, TestMethodSink sink,
            boolean contentHashEnabled, ClassificationOverride override,
            AiResultCache aiCache, CredentialTriageContext secretCtx) throws IOException {

        TestMethodSink effectiveSink = wrapWithExtraSink(sink);
        List<DiscoveredMethod> methods = new ArrayList<>();
        boolean hadErrors = false;
        for (TestDiscovery provider : providers) {
            provider.discover(root).forEach(methods::add);
            if (provider.hadErrors()) {
                hadErrors = true;
            }
        }

        Map<String, List<DiscoveredMethod>> byClass = methods.stream()
                .collect(Collectors.groupingBy(DiscoveredMethod::fqcn,
                        LinkedHashMap::new, Collectors.toList()));

        AiRuntime ai = new AiRuntime(aiOptions, aiEngine, override, aiCache);
        // The prompt-catalogue signature gates cache reuse and tags written entries;
        // only meaningful when AI is enabled.
        String promptSignature = aiEngine == null ? null : aiOptions.promptTemplates().signature();

        Map<String, AiCacheEntry> cacheEntries = new LinkedHashMap<>();
        for (Map.Entry<String, List<DiscoveredMethod>> entry : byClass.entrySet()) {
            processClass(entry, ai, promptSignature, contentHashEnabled, secretCtx,
                    effectiveSink, cacheEntries);
        }

        return new DiscoveryResult(hadErrors, cacheEntries);
    }

    /**
     * Classifies one class (consulting the cache), feeds its methods to {@code effectiveSink}, and —
     * when AI produced a cacheable answer with a content hash — records a cache entry.
     *
     * @param entry           one class's discovered methods, keyed by FQCN
     * @param ai              AI runtime (engine, override, cache)
     * @param promptSignature current prompt-catalogue signature, or {@code null} when AI is disabled
     * @param contentHashEnabled whether the emitted records carry the content hash
     * @param secretCtx       credential-triage context, or {@code null}
     * @param effectiveSink   sink receiving the per-method records
     * @param cacheEntries    accumulator to record the cacheable answer into, keyed by content hash
     * @throws IOException if the sink fails to record a method
     */
    private void processClass(Map.Entry<String, List<DiscoveredMethod>> entry, AiRuntime ai,
            String promptSignature, boolean contentHashEnabled, CredentialTriageContext secretCtx,
            TestMethodSink effectiveSink, Map<String, AiCacheEntry> cacheEntries) throws IOException {
        String fqcn = entry.getKey();
        List<DiscoveredMethod> classMethods = entry.getValue();

        // groupingBy never produces an empty value list, so the first method is always
        // present; it is the representative carrying the class-level fields (source
        // content and file stem are identical across a class's methods).
        DiscoveredMethod representative = classMethods.get(0);
        String classSource = representative.sourceContent().get().orElse(null);

        // The content hash is needed to read the cache, to emit the hash column, and to
        // key a written cache entry — so compute it whenever any of those apply.
        String lookupHash = (contentHashEnabled || ai.cache().isActive() || ai.engine() != null)
                && classSource != null
                ? ContentHasher.hashClass(classSource) : null;
        String outputHash = contentHashEnabled ? lookupHash : null;

        String fileStem = representative.fileStem();
        List<String> methodNames = classMethods.stream().map(DiscoveredMethod::method).toList();
        List<PromptBuilder.TargetMethod> targetMethods = classMethods.stream()
                .map(ScanOrchestrator::toTargetMethod)
                .toList();

        Resolved resolved = resolveSuggestionLookup(fileStem, fqcn, classSource, methodNames,
                targetMethods, ai, lookupHash, promptSignature, secretCtx);
        SuggestionLookup suggestions = resolved.lookup();

        for (DiscoveredMethod m : classMethods) {
            effectiveSink.record(m.fqcn(), m.method(), m.beginLine(), m.loc(), outputHash,
                    m.tags(), m.displayName(),
                    suggestions.find(m.method()).orElse(null));
        }

        if (resolved.cacheable() != null && lookupHash != null) {
            cacheEntries.put(lookupHash, new AiCacheEntry(lookupHash, promptSignature, resolved.cacheable()));
        }
    }

    /**
     * Wraps {@code primary} with {@link CompositeTestMethodSink} when an
     * extra sink was supplied to the constructor; returns {@code primary}
     * unchanged otherwise.
     *
     * @param primary command-supplied primary sink
     * @return effective sink to feed during this discovery pass
     */
    private TestMethodSink wrapWithExtraSink(TestMethodSink primary) {
        if (extraSink == null) {
            return primary;
        }
        return new CompositeTestMethodSink(primary, extraSink);
    }

    /**
     * Collects all discovered methods from every configured root, keyed by
     * source-file path. Methods whose {@link DiscoveredMethod#filePath()} is
     * {@code null} are silently skipped.
     *
     * <p>
     * Providers are passed in pre-loaded so the caller can read each
     * provider's {@link TestDiscovery#hadErrors()} after the call and decide
     * how to propagate the exit code; the caller also owns closing them.
     * </p>
     *
     * @param roots     scan roots; must not be {@code null}
     * @param providers configured and already-loaded discovery providers;
     *                  must not be {@code null}
     * @return mutable map from source-file path to the methods found in that
     *         file; insertion order matches discovery order
     * @throws IOException if directory traversal fails for any root
     */
    @SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops",
            "PMD.CloseResource"}) // providers are owned by the caller
    public Map<Path, List<DiscoveredMethod>> collectMethodsByFile(
            List<Path> roots, List<TestDiscovery> providers) throws IOException {
        Map<Path, List<DiscoveredMethod>> byFile = new LinkedHashMap<>();
        for (Path root : roots) {
            for (TestDiscovery provider : providers) {
                provider.discover(root).forEach(m -> {
                    if (m.filePath() != null) {
                        byFile.computeIfAbsent(m.filePath(), k -> new ArrayList<>()).add(m);
                    }
                });
            }
        }
        return byFile;
    }

    /**
     * Resolves AI security-classification suggestions for every class in
     * {@code byClass} and populates {@code tagsToApply} and
     * {@code displayNames} with the results for methods that are
     * security-relevant.
     *
     * <p>
     * A display-name suggestion is only placed into {@code displayNames}
     * when the discovered method has no existing {@code @DisplayName} in
     * source (i.e. {@link DiscoveredMethod#displayName()} returns
     * {@code null}). This prevents AI-generated names from overwriting
     * manually authored ones.
     * </p>
     *
     * @param byClass      discovered methods grouped by FQCN for one source file
     * @param ai           AI runtime carrying the engine, override, and cache
     * @param aiCache      AI result cache used to compute the content-hash lookup key
     * @param tagsToApply  output accumulator: method name to tag values to write
     * @param displayNames output accumulator: method name to display name to write
     * @since 1.0.0
     */
    public void gatherAiSuggestionsForFile(Map<String, List<DiscoveredMethod>> byClass,
            AiRuntime ai, AiResultCache aiCache,
            Map<String, List<String>> tagsToApply, Map<String, String> displayNames) {
        String promptSignature = ai.engine() == null ? null : ai.options().promptTemplates().signature();
        for (Map.Entry<String, List<DiscoveredMethod>> classEntry : byClass.entrySet()) {
            String fqcn = classEntry.getKey();
            List<DiscoveredMethod> classMethods = classEntry.getValue();

            // groupingBy never produces an empty value list; the first method is the
            // representative carrying the class-level fields (source content and file
            // stem are identical across a class's methods).
            DiscoveredMethod representative = classMethods.get(0);
            String classSource = representative.sourceContent().get().orElse(null);
            String lookupHash = aiCache.isActive() && classSource != null
                    ? ContentHasher.hashClass(classSource) : null;
            String fileStem = representative.fileStem();
            List<String> methodNames = classMethods.stream().map(DiscoveredMethod::method).toList();
            List<PromptBuilder.TargetMethod> targetMethods = classMethods.stream()
                    .map(ScanOrchestrator::toTargetMethod).toList();

            SuggestionLookup suggestions = resolveSuggestionLookup(
                    fileStem, fqcn, classSource, methodNames, targetMethods, ai, lookupHash,
                    promptSignature, null).lookup();

            for (DiscoveredMethod m : classMethods) {
                AiMethodSuggestion suggestion = suggestions.find(m.method()).orElse(null);
                if (suggestion == null || !suggestion.securityRelevant()) {
                    continue;
                }
                if (suggestion.displayName() != null && !suggestion.displayName().isBlank()
                        && m.displayName() == null) {
                    displayNames.putIfAbsent(m.method(), suggestion.displayName());
                }
                if (suggestion.tags() != null && !suggestion.tags().isEmpty()) {
                    tagsToApply.putIfAbsent(m.method(), suggestion.tags());
                }
            }
        }
    }

    /**
     * Wraps a {@link TestMethodSink} so that only records that pass all
     * active filters are forwarded to {@code delegate}.
     *
     * <p>
     * Two independent filters are supported and composed in order:
     * </p>
     * <ol>
     *   <li><b>Security-only filter</b> — when {@code securityOnly} is
     *       {@code true}, records whose {@link AiMethodSuggestion} is
     *       {@code null} or has {@code securityRelevant=false} are dropped.</li>
     *   <li><b>Confidence threshold filter</b> — when {@code confidenceEnabled}
     *       is {@code true} <em>and</em> {@code minConfidence > 0.0}, records
     *       whose {@link AiMethodSuggestion} is {@code null} or has a
     *       {@link AiMethodSuggestion#confidence()} below {@code minConfidence}
     *       are dropped. This filter is a no-op when {@code confidenceEnabled}
     *       is {@code false} because the confidence field is always
     *       {@code 0.0} when confidence scoring was not requested.</li>
     * </ol>
     *
     * <p>
     * When neither filter is active the original {@code delegate} is
     * returned unchanged (zero overhead).
     * </p>
     *
     * @param delegate          the underlying sink to forward matching
     *                          records to
     * @param securityOnly      whether to enable the security-relevance filter
     * @param minConfidence     minimum confidence score (inclusive) required
     *                          to pass the confidence filter; {@code 0.0}
     *                          disables it
     * @param confidenceEnabled whether confidence scoring was requested;
     *                          must be {@code true} for the confidence
     *                          filter to activate
     * @return filtered sink, or {@code delegate} unchanged when all filters
     *         are off
     * @since 1.0.0
     */
    public TestMethodSink filterSink(TestMethodSink delegate, boolean securityOnly,
            double minConfidence, boolean confidenceEnabled) {
        TestMethodSink sink = delegate;
        if (securityOnly) {
            final TestMethodSink next = sink;
            sink = (fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion) -> {
                if (suggestion != null && suggestion.securityRelevant()) {
                    next.record(fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion);
                }
            };
        }
        if (confidenceEnabled && minConfidence > 0.0) {
            final double threshold = minConfidence;
            final TestMethodSink next = sink;
            sink = (fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion) -> {
                if (suggestion != null && suggestion.confidence() >= threshold) {
                    next.record(fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion);
                }
            };
        }
        return sink;
    }

    // -------------------------------------------------------------------------
    // Static utilities
    // -------------------------------------------------------------------------

    /**
     * Converts a single discovered test method into a prompt target descriptor.
     *
     * <p>
     * Exposed publicly because {@link ManualPrepareCommand} also needs to
     * build prompt-target lists from discovered methods when writing manual
     * work files; the conversion logic must stay aligned across both call
     * sites to keep the prompt format consistent.
     * </p>
     *
     * @param m discovered test method; must not be {@code null}
     * @return corresponding prompt target descriptor; never {@code null}
     * @see PromptBuilder.TargetMethod
     */
    public static PromptBuilder.TargetMethod toTargetMethod(DiscoveredMethod m) {
        return new PromptBuilder.TargetMethod(
                m.method(),
                m.beginLine() > 0 ? m.beginLine() : null,
                m.endLine() > 0 ? m.endLine() : null);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the AI answer for one class, consulting the signature-gated cache
     * before any provider call and serving cached credential verdicts when present.
     *
     * @param promptSignature the current run's prompt-catalogue signature, or
     *                        {@code null} when AI is disabled
     * @return the override-applied lookup plus the raw answer to cache (or
     *         {@code null} when nothing should be cached)
     */
    private static Resolved resolveSuggestionLookup(String fileStem, String fqcn,
            String classSource, List<String> methodNames, List<PromptBuilder.TargetMethod> targetMethods,
            AiRuntime ai, String contentHash, String promptSignature, CredentialTriageContext secretCtx) {
        if (methodNames.isEmpty()) {
            return new Resolved(SuggestionLookup.from(null), null);
        }

        if (ai.engine() == null) {
            return new Resolved(SuggestionLookup.from(ai.override().apply(fqcn, null, methodNames)), null);
        }

        List<PromptBuilder.CredentialCandidateRef> candidates =
                secretCtx == null ? List.of() : secretCtx.candidatesFor(fqcn);

        // Cache first, gated on the prompt-catalogue signature. A hit serves the
        // classification AND (when this run triages credentials) the verdicts —
        // from the same cached answer, with no provider call.
        AiClassSuggestion cached = ai.cache().classification(contentHash, promptSignature).orElse(null);
        if (cached != null) {
            AiClassSuggestion cacheable = candidates.isEmpty() ? cached
                    : serveOrTriageVerdicts(ai, fqcn, classSource, contentHash, promptSignature,
                            candidates, cached, secretCtx);
            return new Resolved(SuggestionLookup.from(ai.override().apply(fqcn, cached, methodNames)), cacheable);
        }

        if (classSource == null) {
            return new Resolved(SuggestionLookup.from(ai.override().apply(fqcn, null, methodNames)), null);
        }

        if (ai.options().enabled() && classSource.length() > ai.options().maxClassChars()) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.log(Level.INFO, "Skipping AI for {0}: class source too large ({1} chars)",
                        new Object[] { fqcn, classSource.length() });
            }
            return new Resolved(SuggestionLookup.from(ai.override().apply(fqcn, null, methodNames)), null);
        }

        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "Querying AI for {0} ({1} methods)", new Object[] { fqcn, targetMethods.size() });
        }

        try {
            // Fold credential triage into the single classification call when there
            // are candidates, so the class source is sent to the provider once.
            AiClassSuggestion aiClassSuggestion;
            if (candidates.isEmpty()) {
                aiClassSuggestion = ai.engine().suggestForClass(fileStem, fqcn, classSource, targetMethods);
            } else {
                aiClassSuggestion =
                        ai.engine().suggestForClass(fileStem, fqcn, classSource, targetMethods, candidates);
                secretCtx.recordVerdicts(fqcn, aiClassSuggestion.secrets());
            }
            return new Resolved(
                    SuggestionLookup.from(ai.override().apply(fqcn, aiClassSuggestion, methodNames)),
                    aiClassSuggestion);
        } catch (AiSuggestionException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "AI suggestion failed for class " + fqcn, e);
            }
            return new Resolved(SuggestionLookup.from(ai.override().apply(fqcn, null, methodNames)), null);
        }
    }

    /**
     * On a classification cache hit, supplies the credential verdicts: cached when
     * present for this signature, otherwise a one-off dedicated triage call (so a
     * class whose classification was cached without verdicts still gets scored).
     *
     * @return the cached suggestion augmented with the resolved verdicts, for re-caching
     */
    private static AiClassSuggestion serveOrTriageVerdicts(AiRuntime ai, String fqcn, String classSource,
            String contentHash, String promptSignature,
            List<PromptBuilder.CredentialCandidateRef> candidates, AiClassSuggestion cached,
            CredentialTriageContext secretCtx) {
        Optional<List<CredentialTriageVerdict>> cachedVerdicts =
                ai.cache().verdicts(contentHash, promptSignature);
        if (cachedVerdicts.isPresent()) {
            secretCtx.recordVerdicts(fqcn, cachedVerdicts.get());
            return withSecrets(cached, cachedVerdicts.get());
        }
        if (classSource == null) {
            return cached;
        }
        try {
            List<CredentialTriageVerdict> verdicts = ai.engine().triageSecrets(fqcn, classSource, candidates);
            secretCtx.recordVerdicts(fqcn, verdicts);
            return withSecrets(cached, verdicts);
        } catch (AiSuggestionException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Credential triage failed for cached class " + fqcn, e);
            }
            return cached;
        }
    }

    /**
     * Returns a copy of {@code suggestion} with its credential verdicts replaced.
     *
     * @param suggestion the classification result to copy; never {@code null}
     * @param secrets    the verdicts to attach; may be {@code null}
     * @return a new suggestion carrying {@code secrets}
     */
    private static AiClassSuggestion withSecrets(AiClassSuggestion suggestion,
            List<CredentialTriageVerdict> secrets) {
        return new AiClassSuggestion(suggestion.className(), suggestion.classSecurityRelevant(),
                suggestion.classTags(), suggestion.classReason(), suggestion.methods(), secrets);
    }

    /**
     * The outcome of resolving one class: the override-applied per-method lookup, and
     * the raw AI answer to persist in the cache ({@code null} when nothing should be
     * cached — no AI, no methods, oversized source, or a failed call).
     *
     * @param lookup    per-method suggestion lookup fed to the sink; never {@code null}
     * @param cacheable the full AI answer to cache, or {@code null}
     */
    private record Resolved(SuggestionLookup lookup, AiClassSuggestion cacheable) {
    }

    /**
     * The result of scanning one root: whether any provider errored, and the unified
     * cache entries collected for the classes processed in that root.
     *
     * @param hadErrors    {@code true} if any provider reported a non-fatal error
     * @param cacheEntries cache entries keyed by content hash; never {@code null}
     */
    private record DiscoveryResult(boolean hadErrors, Map<String, AiCacheEntry> cacheEntries) {
    }
}
