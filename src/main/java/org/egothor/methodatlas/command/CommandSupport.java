package org.egothor.methodatlas.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.ClassificationOverride;
import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.TestMethodSink;
import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.egothor.methodatlas.ai.AiSuggestionException;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.ai.SuggestionLookup;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * Shared static infrastructure used by two or more {@link Command} implementations.
 *
 * <p>
 * This utility class centralises provider loading, scan orchestration, AI suggestion
 * resolution, content hashing, and other cross-cutting concerns. All methods are
 * static; this class cannot be instantiated.
 * </p>
 *
 * <p>
 * Methods marked {@code public} ({@link #requireUniqueDiscoveryIds},
 * {@link #requireUniquePatcherIds}, {@link #computeFilePrefix},
 * {@link #buildAiEngine}, {@link #buildAiCache}, and
 * {@link #loadClassificationOverride}) are called either by
 * {@link org.egothor.methodatlas.MethodAtlasApp} (a different package) or
 * directly by unit tests; all other methods are package-private and intended
 * for use within the {@code org.egothor.methodatlas.command} package only.
 * </p>
 */
@SuppressWarnings("PMD.CyclomaticComplexity") // centralised utility class; total CC naturally high across many small methods
public final class CommandSupport {

    private static final Logger LOG = Logger.getLogger(CommandSupport.class.getName());

    private CommandSupport() {
    }

    // -------------------------------------------------------------------------
    // AI runtime bundle (package-private record, shared by scan commands)
    // -------------------------------------------------------------------------

    /**
     * Bundles the AI infrastructure that is constant for the duration of a scan run.
     *
     * @param options  AI configuration
     * @param engine   AI engine; {@code null} when AI is disabled
     * @param override human classification overrides
     * @param cache    AI result cache
     */
    /* default */ record AiRuntime(AiOptions options, AiSuggestionEngine engine,
            ClassificationOverride override, AiResultCache cache) {
    }

    // -------------------------------------------------------------------------
    // Factory / loader methods
    // -------------------------------------------------------------------------

    /**
     * Creates the AI suggestion engine for the current run.
     *
     * <p>
     * Returns {@code null} when AI support is disabled. Initialization failures
     * are wrapped in an {@link IllegalStateException}.
     * </p>
     *
     * @param aiOptions AI configuration for the current run
     * @return initialized AI suggestion engine, or {@code null} when AI is disabled
     * @throws IllegalStateException if engine initialization fails
     */
    public static AiSuggestionEngine buildAiEngine(AiOptions aiOptions) {
        if (!aiOptions.enabled()) {
            return null;
        }
        try {
            return new AiSuggestionEngineImpl(aiOptions);
        } catch (AiSuggestionException e) {
            throw new IllegalStateException("Failed to initialize AI engine", e);
        }
    }

    /**
     * Loads the AI result cache from the given CSV file, or returns the empty
     * no-op cache when no cache file is configured.
     *
     * @param cacheFile path to a previous MethodAtlas CSV output, or {@code null}
     * @return loaded cache; never {@code null}
     * @throws IllegalArgumentException if the file exists but cannot be read or
     *                                  parsed
     */
    public static AiResultCache buildAiCache(Path cacheFile) {
        if (cacheFile == null) {
            return AiResultCache.empty();
        }
        try {
            return AiResultCache.load(cacheFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load AI cache file: " + cacheFile, e);
        }
    }

    /**
     * Loads the classification override file, or returns the empty no-op
     * singleton when no override file is configured.
     *
     * @param overrideFile path to the YAML override file, or {@code null}
     * @return loaded override set; never {@code null}
     * @throws IllegalArgumentException if the file exists but cannot be read or
     *                                  contains invalid YAML
     */
    public static ClassificationOverride loadClassificationOverride(Path overrideFile) {
        if (overrideFile == null) {
            return ClassificationOverride.empty();
        }
        try {
            return ClassificationOverride.load(overrideFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load override file: " + overrideFile, e);
        }
    }

    // -------------------------------------------------------------------------
    // Provider and patcher loading
    // -------------------------------------------------------------------------

    /**
     * Loads all {@link TestDiscovery} providers registered via {@link ServiceLoader},
     * configures each with {@code config}, and returns the list.
     *
     * <p>
     * Providers are discovered from the classpath using the standard
     * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}
     * service file.
     * </p>
     *
     * @param config runtime configuration forwarded to every provider via
     *               {@link TestDiscovery#configure}
     * @return non-empty list of configured providers
     * @throws IllegalStateException if no providers are found on the classpath
     */
    @SuppressWarnings("PMD.CloseResource") // callers are responsible for closing providers via closeAll()
    /* default */ static List<TestDiscovery> loadProviders(TestDiscoveryConfig config) {
        List<TestDiscovery> providers = new ArrayList<>();
        for (TestDiscovery provider : ServiceLoader.load(TestDiscovery.class)) {
            provider.configure(config);
            providers.add(provider);
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "No TestDiscovery providers found on the classpath. "
                    + "Ensure at least one provider JAR ships the service registration file "
                    + "META-INF/services/org.egothor.methodatlas.api.TestDiscovery.");
        }
        requireUniqueDiscoveryIds(providers);
        return providers;
    }

    /**
     * Closes every provider in the list, logging any {@link IOException} at
     * {@link Level#FINE} and continuing so that all providers are attempted.
     *
     * @param providers list of providers to close; never {@code null}
     */
    @SuppressWarnings("PMD.CloseResource") // this method IS the close mechanism; p.close() is called explicitly
    /* default */ static void closeAll(List<TestDiscovery> providers) {
        for (TestDiscovery p : providers) {
            try {
                p.close();
            } catch (IOException e) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "Failed to close provider " + p.pluginId(), e);
                }
            }
        }
    }

    /**
     * Loads all {@link SourcePatcher} providers registered via {@link ServiceLoader},
     * configures each with {@code config}, and returns the list.
     *
     * @param config runtime configuration forwarded to every patcher via
     *               {@link SourcePatcher#configure}
     * @return possibly-empty list of configured patchers
     */
    /* default */ static List<SourcePatcher> loadPatchers(TestDiscoveryConfig config) {
        List<SourcePatcher> patchers = new ArrayList<>();
        for (SourcePatcher patcher : ServiceLoader.load(SourcePatcher.class)) {
            patcher.configure(config);
            patchers.add(patcher);
        }
        requireUniquePatcherIds(patchers);
        return patchers;
    }

    /**
     * Verifies that every {@link TestDiscovery} provider in the list has a
     * unique {@link TestDiscovery#pluginId()}.
     *
     * @param providers list of configured providers
     * @throws IllegalStateException if two or more providers share the same ID
     */
    @SuppressWarnings("PMD.CloseResource") // providers are owned by the caller; this method does not close them
    public static void requireUniqueDiscoveryIds(List<TestDiscovery> providers) {
        Set<String> seen = new LinkedHashSet<>();
        for (TestDiscovery p : providers) {
            String id = p.pluginId();
            if (!seen.add(id)) {
                throw new IllegalStateException(
                        "Duplicate TestDiscovery plugin ID \"" + id + "\": two or more "
                        + "registered providers claim the same pluginId(). "
                        + "Each provider must declare a unique identifier.");
            }
        }
    }

    /**
     * Verifies that every {@link SourcePatcher} in the list has a unique
     * {@link SourcePatcher#pluginId()}.
     *
     * @param patchers list of configured patchers
     * @throws IllegalStateException if two or more patchers share the same ID
     */
    public static void requireUniquePatcherIds(List<SourcePatcher> patchers) {
        Set<String> seen = new LinkedHashSet<>();
        for (SourcePatcher p : patchers) {
            String id = p.pluginId();
            if (!seen.add(id)) {
                throw new IllegalStateException(
                        "Duplicate SourcePatcher plugin ID \"" + id + "\": two or more "
                        + "registered patchers claim the same pluginId(). "
                        + "Each patcher must declare a unique identifier.");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Scan orchestration
    // -------------------------------------------------------------------------

    /**
     * Scans all roots and forwards each discovered test method to {@code sink}.
     *
     * @param roots           source roots to scan
     * @param cliConfig       full parsed CLI configuration
     * @param discoveryConfig discovery configuration forwarded to providers
     * @param aiEngine        AI engine providing suggestions; may be {@code null}
     * @param sink            receiver of discovered test method records
     * @param override        human classification overrides
     * @param aiCache         AI result cache
     * @return {@code 0} if all files were processed successfully, {@code 1} if any
     *         file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    /* default */ static int scan(List<Path> roots, CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, TestMethodSink sink,
            ClassificationOverride override, AiResultCache aiCache) throws IOException {
        List<TestDiscovery> providers = loadProviders(discoveryConfig);
        boolean hadErrors = false;
        try {
            for (Path root : roots) {
                if (runDiscovery(root, providers, cliConfig.aiOptions(), aiEngine, sink,
                        cliConfig.contentHash(), override, aiCache)) {
                    hadErrors = true;
                }
            }
        } finally {
            closeAll(providers);
        }
        return hadErrors ? 1 : 0;
    }

    /**
     * Runs all configured {@link TestDiscovery} providers on {@code root},
     * merges their results, orchestrates AI analysis per class, and forwards
     * each method record to {@code sink}.
     *
     * <p>
     * All providers are run against every root, and their streams are merged
     * before grouping by class. This supports multi-language scanning: a JVM
     * provider and a .NET provider on the classpath will each scan their own
     * file types and contribute distinct {@link DiscoveredMethod} records.
     * </p>
     *
     * @param root               directory to scan
     * @param providers          list of pre-configured discovery providers
     * @param aiOptions          AI configuration for the current run
     * @param aiEngine           AI engine, or {@code null} when AI is disabled
     * @param sink               receiver of discovered test method records
     * @param contentHashEnabled whether to include the class content hash
     * @param override           human classification overrides
     * @param aiCache            AI result cache
     * @return {@code true} if any provider encountered a parse or processing error
     * @throws IOException if traversing the file tree fails
     */
    @SuppressWarnings("PMD.CloseResource") // providers are owned by the caller; this method does not close them
    /* default */ static boolean runDiscovery(Path root, List<TestDiscovery> providers,
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
                    ? computeContentHash(classSource) : null;
            String outputHash = contentHashEnabled ? lookupHash : null;

            String fileStem = classMethods.get(0).fileStem();
            List<String> methodNames = classMethods.stream().map(DiscoveredMethod::method).toList();
            List<PromptBuilder.TargetMethod> targetMethods = classMethods.stream()
                    .map(CommandSupport::toTargetMethod)
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
     * Collects all discovered methods from every root and provider, keyed by
     * source-file path. Methods whose {@link DiscoveredMethod#filePath()} is
     * {@code null} are silently skipped.
     *
     * @param roots     scan roots
     * @param providers configured and already-loaded {@link TestDiscovery} providers
     * @return mutable map from source-file path to the methods found in that file;
     *         insertion order matches discovery order
     * @throws IOException if directory traversal fails for any root
     */
    @SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops",
            "PMD.CloseResource"}) // providers are owned by the caller; this method does not close them
    /* default */ static Map<Path, List<DiscoveredMethod>> collectMethodsByFile(
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
     * {@code byClass} and populates {@code tagsToApply} and {@code displayNames}
     * with the results for methods that are security-relevant.
     *
     * <p>A display-name suggestion is only placed into {@code displayNames} when
     * the discovered method has no existing {@code @DisplayName} in source
     * (i.e. {@link DiscoveredMethod#displayName()} returns {@code null}).
     * This prevents AI-generated names from overwriting manually authored ones.</p>
     *
     * @param byClass      discovered methods grouped by FQCN for one source file
     * @param ai           AI runtime carrying the engine, override, and cache
     * @param aiCache      AI result cache used to compute the content-hash lookup key
     * @param tagsToApply  output accumulator: method name → tag values to write
     * @param displayNames output accumulator: method name → display name to write
     */
    /* default */ static void gatherAiSuggestionsForFile(Map<String, List<DiscoveredMethod>> byClass,
            AiRuntime ai, AiResultCache aiCache,
            Map<String, List<String>> tagsToApply, Map<String, String> displayNames) {
        for (Map.Entry<String, List<DiscoveredMethod>> classEntry : byClass.entrySet()) {
            String fqcn = classEntry.getKey();
            List<DiscoveredMethod> classMethods = classEntry.getValue();

            String classSource = classMethods.get(0).sourceContent().get().orElse(null);
            String lookupHash = aiCache.isActive() && classSource != null
                    ? computeContentHash(classSource) : null;
            String fileStem = classMethods.get(0).fileStem();
            List<String> methodNames = classMethods.stream().map(DiscoveredMethod::method).toList();
            List<PromptBuilder.TargetMethod> targetMethods = classMethods.stream()
                    .map(CommandSupport::toTargetMethod).toList();

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
     * Resolves method-level AI suggestions for a class.
     *
     * <p>
     * Returns an empty lookup when no AI engine is available, the method list is
     * empty, or (for regular provider-based AI) the class source exceeds the
     * configured maximum size. The {@code maxClassChars} limit is only enforced
     * when the automated provider is enabled ({@link AiOptions#enabled()}); it is
     * not applied in the manual consume phase.
     * </p>
     *
     * @param fileStem      dot-separated path stem identifying the source file;
     *                      forwarded to {@link AiSuggestionEngine#suggestForClass}
     * @param fqcn          fully qualified class name
     * @param classSource   pretty-printed source text of the class; may be
     *                      {@code null} when source is unavailable
     * @param methodNames   names of discovered test methods
     * @param targetMethods prompt target descriptors for the test methods
     * @param ai            AI infrastructure for this scan run
     * @param contentHash   hash of the class source for cache lookup; may be
     *                      {@code null}
     * @return lookup of AI suggestions keyed by method name; never {@code null}
     */
    /* default */ static SuggestionLookup resolveSuggestionLookup(String fileStem, String fqcn,
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

    // -------------------------------------------------------------------------
    // Utility methods
    // -------------------------------------------------------------------------

    /**
     * Computes a SHA-256 content fingerprint of a class source string.
     *
     * <p>
     * The hash is derived from the JavaParser pretty-printed form of the class
     * declaration, which normalizes whitespace so that insignificant formatting
     * changes do not alter the fingerprint. The result is a 64-character
     * lowercase hexadecimal string.
     * </p>
     *
     * @param classSource JavaParser pretty-print of the class declaration
     * @return 64-character lowercase hex SHA-256 digest
     * @throws IllegalStateException if SHA-256 is unavailable (never in practice;
     *                               SHA-256 is mandated by the Java SE spec)
     */
    /* default */ static String computeContentHash(String classSource) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(classSource.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Derives the file path prefix used in GitHub Actions workflow command
     * annotations from the first configured scan root.
     *
     * <p>
     * The prefix is made relative to the current working directory so that the
     * resulting annotation paths (e.g. {@code src/test/java/com/acme/AuthTest.java})
     * match what GitHub resolves as inline positions in the PR diff.
     * </p>
     *
     * @param roots configured scan roots; may be empty
     * @return forward-slash path ending with {@code /}, or empty string when
     *         {@code roots} is empty
     */
    public static String computeFilePrefix(List<Path> roots) {
        if (roots.isEmpty()) {
            return "";
        }
        Path root = roots.get(0).toAbsolutePath().normalize();
        String prefix;
        try {
            Path cwd = Paths.get("").toAbsolutePath();
            prefix = cwd.relativize(root).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            // Different drive on Windows — fall back to the absolute path.
            prefix = root.toString().replace('\\', '/');
        }
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix;
    }

    /**
     * Wraps a {@link TestMethodSink} so that only security-relevant records are
     * forwarded to {@code delegate}.
     *
     * <p>
     * When {@code securityOnly} is {@code false} the original {@code delegate} is
     * returned unchanged (zero overhead). When {@code true}, a wrapper is returned
     * that drops any record whose {@link AiMethodSuggestion} is {@code null} or
     * has {@code securityRelevant=false}.
     * </p>
     *
     * @param delegate     the underlying sink to forward matching records to
     * @param securityOnly whether to enable the filter
     * @return filtered sink, or {@code delegate} unchanged when filtering is off
     */
    /* default */ static TestMethodSink filterSink(TestMethodSink delegate, boolean securityOnly) {
        if (!securityOnly) {
            return delegate;
        }
        return (fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion) -> {
            if (suggestion != null && suggestion.securityRelevant()) {
                delegate.record(fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion);
            }
        };
    }

    /**
     * Converts a single discovered test method into a prompt target descriptor.
     *
     * @param m discovered test method
     * @return corresponding prompt target descriptor; never {@code null}
     * @see PromptBuilder.TargetMethod
     */
    /* default */ static PromptBuilder.TargetMethod toTargetMethod(DiscoveredMethod m) {
        return new PromptBuilder.TargetMethod(
                m.method(),
                m.beginLine() > 0 ? m.beginLine() : null,
                m.endLine() > 0 ? m.endLine() : null);
    }

    /**
     * Produces a human-readable string identifying which taxonomy configuration
     * is in effect, for use in scan metadata output.
     *
     * @param aiOptions AI configuration for the current run
     * @param aiActive  whether an AI engine is active for this run
     * @return taxonomy descriptor string; never {@code null}
     */
    /* default */ static String resolveTaxonomyInfo(AiOptions aiOptions, boolean aiActive) {
        if (!aiActive) {
            return "n/a (AI disabled)";
        }
        if (aiOptions.taxonomyFile() != null) {
            return "file:" + aiOptions.taxonomyFile().toAbsolutePath();
        }
        return "built-in/" + aiOptions.taxonomyMode().name().toLowerCase(Locale.ROOT);
    }
}
