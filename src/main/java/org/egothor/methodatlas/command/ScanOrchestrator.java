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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.emit.TestMethodSink;
import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionException;
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
     * Creates a new orchestrator that will resolve providers through
     * {@code pluginLoader}.
     *
     * @param pluginLoader plugin loader used by {@link #scan} and
     *                     {@link #collectMethodsByFile}; must not be
     *                     {@code null}
     */
    public ScanOrchestrator(PluginLoader pluginLoader) {
        this.pluginLoader = pluginLoader;
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
     */
    public int scan(List<Path> roots, CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, TestMethodSink sink,
            ClassificationOverride override, AiResultCache aiCache) throws IOException {
        List<TestDiscovery> providers = pluginLoader.loadProviders(discoveryConfig);
        boolean hadErrors = false;
        try {
            for (Path root : roots) {
                if (runDiscovery(root, providers, cliConfig.aiOptions(), aiEngine, sink,
                        cliConfig.contentHash(), override, aiCache)) {
                    hadErrors = true;
                }
            }
        } finally {
            pluginLoader.closeAll(providers);
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
     */
    @SuppressWarnings("PMD.CloseResource") // providers are owned by the caller; this method does not close them
    public boolean runDiscovery(Path root, List<TestDiscovery> providers,
            AiOptions aiOptions, AiSuggestionEngine aiEngine, TestMethodSink sink,
            boolean contentHashEnabled, ClassificationOverride override,
            AiResultCache aiCache) throws IOException {

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

        for (Map.Entry<String, List<DiscoveredMethod>> entry : byClass.entrySet()) {
            String fqcn = entry.getKey();
            List<DiscoveredMethod> classMethods = entry.getValue();

            String classSource = classMethods.get(0).sourceContent().get().orElse(null);

            String lookupHash = (contentHashEnabled || aiCache.isActive()) && classSource != null
                    ? ContentHasher.hashClass(classSource) : null;
            String outputHash = contentHashEnabled ? lookupHash : null;

            String fileStem = classMethods.get(0).fileStem();
            List<String> methodNames = classMethods.stream().map(DiscoveredMethod::method).toList();
            List<PromptBuilder.TargetMethod> targetMethods = classMethods.stream()
                    .map(ScanOrchestrator::toTargetMethod)
                    .toList();

            SuggestionLookup suggestions = resolveSuggestionLookup(
                    fileStem, fqcn, classSource, methodNames, targetMethods, ai, lookupHash);

            for (DiscoveredMethod m : classMethods) {
                sink.record(m.fqcn(), m.method(), m.beginLine(), m.loc(), outputHash,
                        m.tags(), m.displayName(),
                        suggestions.find(m.method()).orElse(null));
            }
        }

        return hadErrors;
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
     */
    public void gatherAiSuggestionsForFile(Map<String, List<DiscoveredMethod>> byClass,
            AiRuntime ai, AiResultCache aiCache,
            Map<String, List<String>> tagsToApply, Map<String, String> displayNames) {
        for (Map.Entry<String, List<DiscoveredMethod>> classEntry : byClass.entrySet()) {
            String fqcn = classEntry.getKey();
            List<DiscoveredMethod> classMethods = classEntry.getValue();

            String classSource = classMethods.get(0).sourceContent().get().orElse(null);
            String lookupHash = aiCache.isActive() && classSource != null
                    ? ContentHasher.hashClass(classSource) : null;
            String fileStem = classMethods.get(0).fileStem();
            List<String> methodNames = classMethods.stream().map(DiscoveredMethod::method).toList();
            List<PromptBuilder.TargetMethod> targetMethods = classMethods.stream()
                    .map(ScanOrchestrator::toTargetMethod).toList();

            SuggestionLookup suggestions = resolveSuggestionLookup(
                    fileStem, fqcn, classSource, methodNames, targetMethods, ai, lookupHash);

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

    private static SuggestionLookup resolveSuggestionLookup(String fileStem, String fqcn,
            String classSource, List<String> methodNames, List<PromptBuilder.TargetMethod> targetMethods,
            AiRuntime ai, String contentHash) {
        if (methodNames.isEmpty()) {
            return SuggestionLookup.from(null);
        }

        if (ai.engine() == null) {
            return SuggestionLookup.from(ai.override().apply(fqcn, null, methodNames));
        }

        // Check the cache before making an API call.
        AiClassSuggestion cached = ai.cache().lookup(contentHash).orElse(null);
        if (cached != null) {
            return SuggestionLookup.from(ai.override().apply(fqcn, cached, methodNames));
        }

        if (classSource == null) {
            return SuggestionLookup.from(ai.override().apply(fqcn, null, methodNames));
        }

        if (ai.options().enabled() && classSource.length() > ai.options().maxClassChars()) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.log(Level.INFO, "Skipping AI for {0}: class source too large ({1} chars)",
                        new Object[] { fqcn, classSource.length() });
            }
            return SuggestionLookup.from(ai.override().apply(fqcn, null, methodNames));
        }

        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "Querying AI for {0} ({1} methods)", new Object[] { fqcn, targetMethods.size() });
        }

        try {
            AiClassSuggestion aiClassSuggestion =
                    ai.engine().suggestForClass(fileStem, fqcn, classSource, targetMethods);
            return SuggestionLookup.from(ai.override().apply(fqcn, aiClassSuggestion, methodNames));
        } catch (AiSuggestionException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "AI suggestion failed for class " + fqcn, e);
            }
            return SuggestionLookup.from(ai.override().apply(fqcn, null, methodNames));
        }
    }
}
