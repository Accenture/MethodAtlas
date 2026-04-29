package org.egothor.methodatlas;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.egothor.methodatlas.ai.AiSuggestionException;
import org.egothor.methodatlas.ai.ManualConsumeEngine;
import org.egothor.methodatlas.ai.ManualPrepareEngine;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.ai.SuggestionLookup;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.emit.DeltaEmitter;
import org.egothor.methodatlas.emit.GitHubAnnotationsEmitter;
import org.egothor.methodatlas.emit.OutputEmitter;
import org.egothor.methodatlas.emit.SarifEmitter;

/**
 * Command-line application for scanning Java test sources, extracting JUnit
 * test metadata, and optionally enriching the emitted results with AI-generated
 * security tagging suggestions.
 *
 * <p>
 * The application traverses one or more directory roots, parses matching source
 * files using JavaParser, identifies supported JUnit Jupiter test methods, and
 * emits one output record per discovered test method. File selection matches
 * source files whose names end with the configured suffix (default:
 * {@code Test.java}).
 * </p>
 *
 * <h2>Source-Derived Metadata</h2>
 *
 * <p>
 * For each discovered test method, the application reports:
 * </p>
 * <ul>
 * <li>fully qualified class name</li>
 * <li>method name</li>
 * <li>inclusive line count of the method declaration</li>
 * <li>JUnit {@code @Tag} values declared on the method</li>
 * </ul>
 *
 * <h2>AI Enrichment</h2>
 *
 * <p>
 * When AI support is enabled, the application submits each discovered test
 * class to an {@link org.egothor.methodatlas.ai.AiSuggestionEngine} and merges
 * the returned method-level suggestions into the emitted output.
 * </p>
 *
 * <h2>Manual AI Workflow</h2>
 *
 * <p>
 * Operators who cannot access an AI API directly can use the two-phase manual
 * workflow:
 * </p>
 * <ol>
 * <li><b>Prepare phase</b> ({@code -manual-prepare}): the application scans
 * test sources and writes one work file per class to the specified directory.
 * Each work file contains operator instructions and the full AI prompt (with
 * class source embedded). No CSV output is produced in this phase.</li>
 * <li><b>Consume phase</b> ({@code -manual-consume}): the application reads
 * operator-saved AI response files ({@code <stem>.response.txt}) from the
 * response directory and produces the final enriched CSV. Classes whose
 * response file is absent receive empty AI columns.</li>
 * </ol>
 *
 * <h2>Supported Command-Line Options</h2>
 *
 * <ul>
 * <li>{@code -config <path>} — loads default values from a YAML configuration
 * file; command-line flags override YAML values</li>
 * <li>{@code -plain} — emits plain text output instead of CSV</li>
 * <li>{@code -sarif} — emits SARIF 2.1.0 JSON output</li>
 * <li>{@code -ai} — enables AI-based enrichment</li>
 * <li>{@code -ai-provider <provider>} — selects the AI provider</li>
 * <li>{@code -ai-model <model>} — selects the provider-specific model</li>
 * <li>{@code -ai-base-url <url>} — overrides the provider base URL</li>
 * <li>{@code -ai-api-key <key>} — supplies the AI API key directly</li>
 * <li>{@code -ai-api-key-env <name>} — resolves the AI API key from an
 * environment variable</li>
 * <li>{@code -ai-taxonomy <path>} — loads taxonomy text from an external
 * file</li>
 * <li>{@code -ai-taxonomy-mode <mode>} — selects the built-in taxonomy
 * variant</li>
 * <li>{@code -ai-max-class-chars <count>} — limits class source size submitted
 * to AI</li>
 * <li>{@code -ai-timeout-sec <seconds>} — sets the AI request timeout</li>
 * <li>{@code -ai-max-retries <count>} — sets the retry limit for AI
 * operations</li>
 * <li>{@code -ai-confidence} — requests a confidence score for each AI
 * security classification; adds an {@code ai_confidence} column to the
 * output</li>
 * <li>{@code -ai-cache <path>} — loads a previous scan CSV produced with
 * {@code -content-hash -ai} as an AI result cache; classes whose
 * {@code content_hash} matches a cache entry are classified without an API
 * call; changed and new classes are classified normally</li>
 * <li>{@code -file-suffix <suffix>} — matches source files by name suffix
 * (default: {@code Test.java}); may be repeated to match multiple patterns,
 * e.g. {@code -file-suffix Test.java -file-suffix IT.java}; the first
 * occurrence replaces the default</li>
 * <li>{@code -test-annotation <name>} — recognises methods annotated with
 * {@code name} as test methods; may be repeated; the first occurrence replaces
 * the JVM provider's default set (JUnit 5 {@code Test}, {@code ParameterizedTest},
 * {@code RepeatedTest}, {@code TestFactory}, {@code TestTemplate})</li>
 * <li>{@code -emit-metadata} — emits {@code # key: value} comment lines
 * before the header row describing the tool version, scan timestamp, and
 * taxonomy configuration</li>
 * <li>{@code -apply-tags} — instead of emitting a report, writes
 * AI-generated {@code @DisplayName} and {@code @Tag} annotations back to
 * the scanned source files; requires AI enrichment to be enabled</li>
 * <li>{@code -content-hash} — includes a SHA-256 fingerprint of each class
 * source as a {@code content_hash} column in CSV/plain output and as a SARIF
 * property; useful for detecting which classes changed between scans</li>
 * <li>{@code -security-only} — suppresses non-security methods from the output;
 * only methods whose AI classification (or override) has
 * {@code securityRelevant=true} are emitted; requires AI enrichment or a
 * classification override file to have any effect</li>
 * <li>{@code -manual-prepare <workdir> <responsedir>} — runs the manual AI
 * prepare phase, writing work files to {@code workdir} and empty response stubs
 * to {@code responsedir}; the two paths may be identical</li>
 * <li>{@code -manual-consume <workdir> <responsedir>} — runs the manual AI
 * consume phase, reading response files from {@code responsedir} and emitting
 * the final enriched CSV</li>
 * <li>{@code -diff <before.csv> <after.csv>} — compares two MethodAtlas scan
 * outputs and emits a delta report showing added, removed, and modified test
 * methods; all other flags are ignored when {@code -diff} is present</li>
 * <li>{@code -apply-tags-from-csv <path>} — instead of emitting a report,
 * applies the annotation decisions recorded in the reviewed CSV back to the
 * source files; the CSV is treated as a complete desired-state specification:
 * every test method's {@code @Tag} set and {@code @DisplayName} are driven
 * entirely by the corresponding CSV row</li>
 * <li>{@code -mismatch-limit <n>} — when used with {@code -apply-tags-from-csv},
 * aborts without modifying any source file if the number of mismatches between
 * the CSV and the current source tree reaches or exceeds {@code n}; {@code -1}
 * (the default) logs mismatches as warnings and proceeds</li>
 * <li>{@code -emit-source-root} — adds a {@code source_root} column to CSV
 * output and a {@code SRCROOT=} token to plain-text output, identifying which
 * scan root each record originated from; essential in multi-root or monorepo
 * projects where the same fully qualified class name can appear under different
 * source trees (e.g. {@code module-a/src/test/java/} and
 * {@code module-b/src/test/java/}); has no effect on SARIF or GitHub Annotations
 * output</li>
 * </ul>
 *
 * <p>
 * Any remaining non-option arguments are interpreted as root paths to scan. If
 * no scan path is supplied, the current working directory is scanned.
 * </p>
 *
 * <h2>Exit Codes</h2>
 *
 * <ul>
 * <li>{@code 0} — all files processed successfully</li>
 * <li>{@code 1} — one or more files could not be parsed or processed</li>
 * </ul>
 *
 * @see org.egothor.methodatlas.ai.AiSuggestionEngine
 * @see org.egothor.methodatlas.api.SourcePatcher
 * @see org.egothor.methodatlas.emit.OutputEmitter
 * @see org.egothor.methodatlas.emit.SarifEmitter
 * @see #main(String[])
 */
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class MethodAtlasApp {

    private static final Logger LOG = Logger.getLogger(MethodAtlasApp.class.getName());
    private static final String FLAG_DIFF = "-diff";

    /**
     * Prevents instantiation of this utility class.
     */
    private MethodAtlasApp() {
    }

    /**
     * Program entry point.
     *
     * <p>
     * Delegates all work to {@link #run(String[], PrintWriter)}. Exits with a
     * non-zero status code if any source file could not be processed.
     * </p>
     *
     * @param args command-line arguments
     * @throws IOException              if traversal of a configured file tree fails
     * @throws IllegalArgumentException if an option is unknown, if a required
     *                                  option value is missing, or if an option
     *                                  value cannot be parsed
     * @throws IllegalStateException    if AI support is enabled but the AI engine
     *                                  cannot be created successfully
     */
    public static void main(String[] args) throws IOException {
        // Wrap System.out in a guarded stream whose close() only flushes.
        // This lets try-with-resources manage the PrintWriter (satisfying
        // SpotBugs CloseResource and PMD UseTryWithResources) without
        // permanently closing System.out (satisfying Error Prone's
        // ClosingStandardOutputStreams check).
        OutputStream guarded = new FilterOutputStream(System.out) {
            @Override
            public void close() throws IOException {
                flush(); // flush but do NOT close System.out
            }
        };
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(guarded, StandardCharsets.UTF_8), true)) {
            int exitCode = run(args, out);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        }
    }

    /**
     * Executes a full application run, emitting all output to the supplied writer.
     *
     * <p>
     * This method is the primary entry point for programmatic and test use. It
     * parses arguments, initialises the parser and AI engine, emits headers, and
     * scans the requested paths.
     * </p>
     *
     * <p>
     * When the manual prepare phase is active ({@code -manual-prepare}) this method
     * writes work files and reports progress to {@code out} instead of emitting CSV.
     * When the manual consume phase is active ({@code -manual-consume}) this method
     * uses {@link ManualConsumeEngine} to read operator-saved responses and emits
     * the standard enriched CSV.
     * </p>
     *
     * @param args command-line arguments
     * @param out  writer that receives all emitted output
     * @return {@code 0} if all files were processed successfully, {@code 1} if
     *         any file produced a parse or processing error
     * @throws IOException              if traversal of a configured file tree fails
     * @throws IllegalArgumentException if an option is unknown, if a required
     *                                  option value is missing, or if an option
     *                                  value cannot be parsed
     * @throws IllegalStateException    if AI support is enabled but the AI engine
     *                                  cannot be created successfully
     */
    @SuppressWarnings("PMD.NPathComplexity")
    /* default */ static int run(String[] args, PrintWriter out) throws IOException {
        // -diff is handled before full argument parsing; all other flags are ignored.
        for (int i = 0; i < args.length; i++) {
            if (FLAG_DIFF.equals(args[i])) {
                if (i + 2 >= args.length) {
                    throw new IllegalArgumentException(
                            "-diff requires two arguments: -diff <before.csv> <after.csv>");
                }
                return runDiff(Path.of(args[i + 1]), Path.of(args[i + 2]), out);
            }
        }

        CliConfig cliConfig = CliArgs.parse(args);
        ClassificationOverride override = loadClassificationOverride(cliConfig.overrideFile());
        AiResultCache aiCache = buildAiCache(cliConfig.aiCacheFile());

        TestDiscoveryConfig discoveryConfig =
                new TestDiscoveryConfig(cliConfig.fileSuffixes(), cliConfig.testMarkers(), cliConfig.properties());

        // Manual prepare phase: write AI prompt work files; no CSV output.
        if (cliConfig.manualMode() instanceof ManualMode.Prepare prepare) {
            return runManualPrepare(prepare, cliConfig, discoveryConfig, out);
        }

        // Determine AI engine: manual consume reads from files; normal mode calls APIs.
        AiSuggestionEngine aiEngine;
        if (cliConfig.manualMode() instanceof ManualMode.Consume consume) {
            aiEngine = new ManualConsumeEngine(consume.responseDir());
        } else {
            aiEngine = buildAiEngine(cliConfig.aiOptions());
        }

        boolean aiEnabled = aiEngine != null;
        boolean confidenceEnabled = aiEnabled && cliConfig.aiOptions().confidence();
        boolean contentHashEnabled = cliConfig.contentHash();

        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();

        // Apply-tags-from-csv mode: apply reviewed CSV decisions to source files.
        if (cliConfig.applyTagsFromCsvFile() != null) {
            List<SourcePatcher> patchers = loadPatchers(discoveryConfig);
            return runApplyTagsFromCsv(cliConfig, patchers, roots, out);
        }

        // Apply-tags mode: annotate source files; no report emitted.
        if (cliConfig.applyTags()) {
            List<SourcePatcher> patchers = loadPatchers(discoveryConfig);
            return runApplyTags(cliConfig, aiEngine, roots, out, override, aiCache, patchers);
        }

        // SARIF mode: buffer all records; write JSON once after the scan completes.
        if (cliConfig.outputMode() == OutputMode.SARIF) {
            return runSarif(cliConfig, discoveryConfig, aiEngine, aiEnabled, confidenceEnabled, roots, out, override, aiCache);
        }

        // GitHub Annotations mode: emit ::notice/::warning workflow commands.
        if (cliConfig.outputMode() == OutputMode.GITHUB_ANNOTATIONS) {
            return runGitHubAnnotations(cliConfig, discoveryConfig, aiEngine, roots, out, override, aiCache);
        }

        // CSV / PLAIN mode: emit incrementally.
        OutputEmitter emitter = new OutputEmitter(out, aiEnabled, confidenceEnabled, contentHashEnabled,
                cliConfig.driftDetect(), cliConfig.emitSourceRoot());

        if (cliConfig.emitMetadata()) {
            String version = MethodAtlasApp.class.getPackage().getImplementationVersion();
            String taxonomyInfo = resolveTaxonomyInfo(cliConfig.aiOptions(), aiEnabled);
            emitter.emitMetadata(version != null ? version : "dev", Instant.now().toString(), taxonomyInfo);
        }

        emitter.emitCsvHeader(cliConfig.outputMode());

        final OutputMode mode = cliConfig.outputMode();
        final boolean emitSourceRoot = cliConfig.emitSourceRoot();

        // Scan each root with its own sink so the source_root value can be captured
        // per root. When emitSourceRoot is false, sourceRoot is null and the column
        // is omitted from the output.
        List<TestDiscovery> providers = loadProviders(discoveryConfig);
        boolean hadErrors = false;
        try {
            for (Path root : roots) {
                String sourceRoot = emitSourceRoot ? computeFilePrefix(List.of(root)) : null;
                TestMethodSink rootSink = (fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion) ->
                        emitter.emit(mode, fqcn, method, loc, contentHash, tags, displayName, suggestion, sourceRoot);
                if (runDiscovery(root, providers, cliConfig.aiOptions(), aiEngine,
                        filterSink(rootSink, cliConfig.securityOnly()),
                        cliConfig.contentHash(), override, aiCache)) {
                    hadErrors = true;
                }
            }
        } finally {
            closeAll(providers);
        }
        int result = hadErrors ? 1 : 0;

        if (aiCache.isActive() && LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "AI cache: {0} hit(s), {1} miss(es)",
                    new Object[] { aiCache.hits(), aiCache.misses() });
        }
        return result;
    }

    /**
     * Runs the delta report path: compares two scan CSV outputs and emits the
     * difference.
     *
     * @param before path to the <em>before</em> scan CSV
     * @param after  path to the <em>after</em> scan CSV
     * @param out    writer that receives the delta report
     * @return {@code 0} always; errors reading the files propagate as exceptions
     * @throws IOException              if either file cannot be read
     * @throws IllegalArgumentException if a required CSV column is absent
     */
    private static int runDiff(Path before, Path after, PrintWriter out) throws IOException {
        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);
        DeltaEmitter.emit(result, out);
        return 0;
    }

    /**
     * Applies reviewed CSV annotation decisions to test method source files.
     *
     * <p>
     * Delegates all work to {@link ApplyTagsFromCsvEngine#apply}. The CSV file is
     * interpreted as a complete desired-state specification: each row drives the
     * exact set of {@code @Tag} annotations and the {@code @DisplayName} text that
     * the corresponding method should have after the run.
     * </p>
     *
     * @param cliConfig full parsed CLI configuration
     * @param patchers  list of configured {@link SourcePatcher} implementations
     * @param roots     source roots to scan
     * @param log       writer for progress and summary output
     * @return {@code 0} on success, {@code 1} when the mismatch limit is exceeded
     *         or a fatal error occurs
     * @throws IOException if the CSV or source files cannot be read or written
     */
    private static int runApplyTagsFromCsv(CliConfig cliConfig, List<SourcePatcher> patchers,
            List<Path> roots, PrintWriter log) throws IOException {
        return ApplyTagsFromCsvEngine.apply(
                cliConfig.applyTagsFromCsvFile(),
                roots,
                cliConfig.mismatchLimit(),
                patchers,
                log);
    }

    /**
     * Applies AI-generated annotations to test method source files.
     *
     * <p>
     * Discovers test methods via the configured {@link TestDiscovery} providers,
     * resolves AI suggestions for each class, and delegates the actual source
     * file write-back to the matching {@link SourcePatcher} implementation. A
     * summary line is written to {@code log} on completion.
     * </p>
     *
     * @param cliConfig full parsed CLI configuration
     * @param aiEngine  AI engine providing suggestions; may be {@code null}
     * @param roots     source roots to scan
     * @param log       writer for progress and summary output
     * @param override  human classification overrides
     * @param aiCache   AI result cache
     * @param patchers  list of configured {@link SourcePatcher} implementations
     * @return {@code 0} if all files were processed successfully, {@code 1}
     *         if any file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static int runApplyTags(CliConfig cliConfig, AiSuggestionEngine aiEngine,
            List<Path> roots, PrintWriter log,
            ClassificationOverride override, AiResultCache aiCache,
            List<SourcePatcher> patchers) throws IOException {

        TestDiscoveryConfig discoveryConfig =
                new TestDiscoveryConfig(cliConfig.fileSuffixes(), cliConfig.testMarkers(), cliConfig.properties());
        List<TestDiscovery> providers = loadProviders(discoveryConfig);
        // Initializers are omitted: both variables are assigned unconditionally in the try
        // block, which satisfies JLS §16.2.15 definite-assignment for try-finally without
        // a catch clause. The providers list is closed by closeAll() in the finally block.
        Map<Path, List<DiscoveredMethod>> byFile;
        boolean hadErrors;
        try {
            byFile = collectMethodsByFile(roots, providers);
            hadErrors = providers.stream().anyMatch(TestDiscovery::hadErrors);
        } finally {
            closeAll(providers);
        }
        AiRuntime ai = new AiRuntime(cliConfig.aiOptions(), aiEngine, override, aiCache);

        int modifiedFiles = 0;
        int totalAnnotations = 0;

        for (Map.Entry<Path, List<DiscoveredMethod>> entry : byFile.entrySet()) {
            Path sourceFile = entry.getKey();
            List<DiscoveredMethod> methods = entry.getValue();

            SourcePatcher patcher = patchers.stream()
                    .filter(p -> p.supports(sourceFile))
                    .findFirst().orElse(null);
            if (patcher == null) {
                continue;
            }

            Map<String, List<DiscoveredMethod>> byClass = methods.stream()
                    .collect(Collectors.groupingBy(DiscoveredMethod::fqcn,
                            LinkedHashMap::new, Collectors.toList()));

            Map<String, List<String>> tagsToApply = new LinkedHashMap<>();
            Map<String, String> displayNames = new LinkedHashMap<>();

            gatherAiSuggestionsForFile(byClass, ai, aiCache, tagsToApply, displayNames);

            if (!tagsToApply.isEmpty() || !displayNames.isEmpty()) {
                try {
                    int changes = patcher.patch(sourceFile, tagsToApply, displayNames, log);
                    if (changes > 0) {
                        modifiedFiles++;
                        totalAnnotations += changes;
                    }
                } catch (IOException e) {
                    if (LOG.isLoggable(Level.WARNING)) {
                        LOG.log(Level.WARNING, "Cannot process: " + sourceFile, e);
                    }
                    hadErrors = true;
                }
            }
        }

        log.println("Apply-tags complete: " + totalAnnotations + " annotation(s) added to "
                + modifiedFiles + " file(s)");
        return hadErrors ? 1 : 0;
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
    private static Map<Path, List<DiscoveredMethod>> collectMethodsByFile(
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
    private static void gatherAiSuggestionsForFile(Map<String, List<DiscoveredMethod>> byClass,
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
                    .map(MethodAtlasApp::toTargetMethod).toList();

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
     * Runs the SARIF output path: scans all roots, then serializes the buffered
     * records as a single SARIF document.
     *
     * @param cliConfig         full parsed CLI configuration
     * @param aiEngine          AI engine providing suggestions; may be {@code null}
     * @param aiEnabled         whether an AI engine is active
     * @param confidenceEnabled whether the {@code aiConfidence} property should be
     *                          included in SARIF properties and message text
     * @param roots             source roots to scan
     * @param out               writer that receives the serialized SARIF document
     * @return {@code 0} if all files were processed successfully, {@code 1} if any
     *         file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    private static int runSarif(CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine,
            boolean aiEnabled, boolean confidenceEnabled,
            List<Path> roots, PrintWriter out,
            ClassificationOverride override, AiResultCache aiCache) throws IOException {
        String filePrefix = computeFilePrefix(roots);
        boolean scoresInMessage = !cliConfig.sarifOmitScores();
        SarifEmitter sarifEmitter = new SarifEmitter(aiEnabled, confidenceEnabled, filePrefix,
                scoresInMessage);
        int result = scan(roots, cliConfig, discoveryConfig, aiEngine,
                filterSink(sarifEmitter, cliConfig.securityOnly()), override, aiCache);
        sarifEmitter.flush(out);
        return result;
    }

    /**
     * Runs the GitHub Annotations output path: emits {@code ::notice} and
     * {@code ::warning} workflow commands for security-relevant test methods.
     *
     * @param cliConfig full parsed CLI configuration
     * @param aiEngine  AI engine providing suggestions; may be {@code null}
     * @param roots     source roots to scan
     * @param out       writer that receives the workflow command lines
     * @return {@code 0} if all files were processed successfully, {@code 1} if any
     *         file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    private static int runGitHubAnnotations(CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine,
            List<Path> roots, PrintWriter out,
            ClassificationOverride override, AiResultCache aiCache) throws IOException {
        String filePrefix = computeFilePrefix(roots);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, filePrefix);
        return scan(roots, cliConfig, discoveryConfig, aiEngine, emitter, override, aiCache);
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
    /* default */ static String computeFilePrefix(List<Path> roots) {
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
     * Scans all roots and forwards each discovered test method to {@code sink}.
     *
     * @param roots     source roots to scan
     * @param cliConfig full parsed CLI configuration
     * @param aiEngine  AI engine providing suggestions; may be {@code null}
     * @param sink      receiver of discovered test method records
     * @return {@code 0} if all files were processed successfully, {@code 1} if any
     *         file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    private static int scan(List<Path> roots, CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine,
            TestMethodSink sink, ClassificationOverride override,
            AiResultCache aiCache) throws IOException {
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
    private static boolean runDiscovery(Path root, List<TestDiscovery> providers,
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
                    .map(MethodAtlasApp::toTargetMethod)
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
     * Loads all {@link TestDiscovery} providers registered via
     * {@link ServiceLoader}, configures each with {@code config}, and returns
     * the list.
     *
     * <p>
     * Providers are discovered from the classpath using the standard
     * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}
     * service file.  Adding a provider JAR to the classpath automatically
     * enables the corresponding language/framework support without any code
     * change in the application.
     * </p>
     *
     * @param config runtime configuration forwarded to every provider via
     *               {@link TestDiscovery#configure}
     * @return non-empty list of configured providers
     * @throws IllegalStateException if no providers are found on the classpath
     */
    @SuppressWarnings("PMD.CloseResource") // callers are responsible for closing providers via closeAll()
    private static List<TestDiscovery> loadProviders(TestDiscoveryConfig config) {
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
     * <p>
     * This is a best-effort shutdown: a failure to close one provider does not
     * prevent subsequent providers from being closed. In practice, close failures
     * for stateless providers (whose default {@code close()} is a no-op) are
     * impossible; they can only occur in providers that manage external resources
     * such as sub-process pools.
     * </p>
     *
     * @param providers list of providers to close; never {@code null}
     */
    @SuppressWarnings("PMD.CloseResource") // this method IS the close mechanism; p.close() is called explicitly
    private static void closeAll(List<TestDiscovery> providers) {
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
     * Verifies that every {@link TestDiscovery} provider in the list has a
     * unique {@link TestDiscovery#pluginId()}.
     *
     * <p>Package-private to allow direct invocation from unit tests.</p>
     *
     * @param providers list of configured providers
     * @throws IllegalStateException if two or more providers share the same ID
     */
    @SuppressWarnings("PMD.CloseResource") // providers are owned by the caller; this method does not close them
    /* default */ static void requireUniqueDiscoveryIds(List<TestDiscovery> providers) {
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
     * Loads all {@link SourcePatcher} providers registered via
     * {@link ServiceLoader}, configures each with {@code config}, and returns
     * the list.
     *
     * <p>
     * Providers are discovered from the classpath using the standard
     * {@code META-INF/services/org.egothor.methodatlas.api.SourcePatcher}
     * service file. An empty list is returned when no patchers are found;
     * this is not an error — some modes (e.g. scan-only) do not require patchers.
     * </p>
     *
     * @param config runtime configuration forwarded to every patcher via
     *               {@link SourcePatcher#configure}
     * @return possibly-empty list of configured patchers
     */
    private static List<SourcePatcher> loadPatchers(TestDiscoveryConfig config) {
        List<SourcePatcher> patchers = new ArrayList<>();
        for (SourcePatcher patcher : ServiceLoader.load(SourcePatcher.class)) {
            patcher.configure(config);
            patchers.add(patcher);
        }
        requireUniquePatcherIds(patchers);
        return patchers;
    }

    /**
     * Verifies that every {@link SourcePatcher} in the list has a unique
     * {@link SourcePatcher#pluginId()}.
     *
     * <p>Package-private to allow direct invocation from unit tests.</p>
     *
     * @param patchers list of configured patchers
     * @throws IllegalStateException if two or more patchers share the same ID
     */
    /* default */ static void requireUniquePatcherIds(List<SourcePatcher> patchers) {
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

    /**
     * Executes the manual AI prepare phase.
     *
     * <p>
     * Discovers test classes via the configured {@link TestDiscovery} providers,
     * then writes one work file per class to the prepare work directory.
     * Progress lines are written to {@code log}. No CSV output is produced.
     * </p>
     *
     * @param prepare         manual prepare mode configuration
     * @param cliConfig       full parsed CLI configuration (used for paths, suffix,
     *                        and taxonomy options)
     * @param discoveryConfig discovery configuration forwarded to providers
     * @param log             writer used for progress reporting
     * @return {@code 0} if all files were processed successfully, {@code 1} if any
     *         provider encountered a processing error
     * @throws IOException if traversing a file tree fails
     */
    @SuppressWarnings("PMD.CloseResource") // providers closed by closeAll() in the finally block below
    private static int runManualPrepare(ManualMode.Prepare prepare, CliConfig cliConfig,
            TestDiscoveryConfig discoveryConfig, PrintWriter log) throws IOException {
        ManualPrepareEngine engine;
        try {
            engine = new ManualPrepareEngine(prepare.workDir(), prepare.responseDir(), cliConfig.aiOptions());
        } catch (AiSuggestionException e) {
            throw new IllegalStateException("Failed to initialize manual prepare engine", e);
        }

        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();
        List<TestDiscovery> providers = loadProviders(discoveryConfig);
        boolean hadErrors = false;
        int prepared = 0;

        try {
            for (Path root : roots) {
                List<DiscoveredMethod> allMethods = new ArrayList<>(); // NOPMD - intentionally one list per scan root
                for (TestDiscovery provider : providers) {
                    provider.discover(root).forEach(allMethods::add);
                    if (provider.hadErrors()) {
                        hadErrors = true;
                    }
                }

                // Group by FQCN so each class produces one work file
                Map<String, List<DiscoveredMethod>> byClass = allMethods.stream()
                        .collect(Collectors.groupingBy(DiscoveredMethod::fqcn,
                                LinkedHashMap::new, Collectors.toList()));

                for (Map.Entry<String, List<DiscoveredMethod>> entry : byClass.entrySet()) {
                    String fqcn = entry.getKey();
                    List<DiscoveredMethod> classMethods = entry.getValue();
                    String classSource = classMethods.get(0).sourceContent().get().orElse(null);
                    if (classSource == null) {
                        continue;
                    }
                    String fileStem = classMethods.get(0).fileStem();
                    List<PromptBuilder.TargetMethod> targetMethods = classMethods.stream()
                            .map(MethodAtlasApp::toTargetMethod).toList();
                    try {
                        Path workFile = engine.prepare(fileStem, fqcn, classSource, targetMethods);
                        log.println("Prepared: " + workFile);
                        prepared++;
                    } catch (AiSuggestionException e) {
                        if (LOG.isLoggable(Level.WARNING)) {
                            LOG.log(Level.WARNING, "Failed to prepare work file for " + fqcn, e);
                        }
                    }
                }
            }
        } finally {
            closeAll(providers);
        }

        log.println("Manual prepare complete. Wrote " + prepared + " work file(s) to " + prepare.workDir()
                + " (response stubs in " + prepare.responseDir() + ")");
        return hadErrors ? 1 : 0;
    }

    /**
     * Computes the dot-separated file stem used to name work and response files
     * in the manual AI workflow.
     *
     * <p>
     * The stem is derived from the source file's path relative to the scan root,
     * with path separators replaced by {@code .} and the {@code .java} extension
     * removed. This makes each stem unique within a scan root, and when scanning
     * from a project root that contains multiple modules the module directory name
     * is naturally included in the stem (e.g.
     * {@code module-a.src.test.java.com.acme.FooTest}).
     * </p>
     *
     * <p>
     * When a file contains inner classes whose simple name differs from the file
     * name, the FQCN's simple name is appended so that each class in the file
     * receives a distinct stem (e.g. {@code com.acme.FooTest.BarTest}).
     * </p>
     *
     * @param root  scan root directory
     * @param file  source file being processed
     * @param fqcn  fully qualified class name of the class in {@code file}
     * @return dot-separated file stem; never {@code null}
     */
    /* default */ static String buildFileStem(Path root, Path file, String fqcn) {
        Path rel = root.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize());
        String pathStr = rel.toString().replace('\\', '/').replace('/', '.');
        if (pathStr.endsWith(".java")) {
            pathStr = pathStr.substring(0, pathStr.length() - 5);
        }
        // For inner classes the file name encodes the outer class but the FQCN ends
        // with the inner class name; append it to keep stems distinct per class.
        String pathLastPart = pathStr.contains(".")
                ? pathStr.substring(pathStr.lastIndexOf('.') + 1) : pathStr;
        String fqcnLastPart = fqcn.contains(".")
                ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
        if (!pathLastPart.equals(fqcnLastPart)) {
            return pathStr + "." + fqcnLastPart;
        }
        return pathStr;
    }

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
    private static String computeContentHash(String classSource) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(classSource.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Bundles the AI infrastructure that is constant for the duration of a scan run.
     *
     * @param options  AI configuration
     * @param engine   AI engine; {@code null} when AI is disabled
     * @param override human classification overrides
     * @param cache    AI result cache
     */
    private record AiRuntime(AiOptions options, AiSuggestionEngine engine,
            ClassificationOverride override, AiResultCache cache) {}

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
            AiClassSuggestion aiClassSuggestion = ai.engine().suggestForClass(fileStem, fqcn, classSource, targetMethods);
            return SuggestionLookup.from(ai.override().apply(fqcn, aiClassSuggestion, methodNames));
        } catch (AiSuggestionException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "AI suggestion failed for class " + fqcn, e);
            }
            return SuggestionLookup.from(ai.override().apply(fqcn, null, methodNames));
        }
    }

    /**
     * Loads the classification override file, or returns the empty no-op singleton
     * when no override file is configured.
     *
     * @param overrideFile path to the YAML override file, or {@code null}
     * @return loaded override set; never {@code null}
     * @throws IllegalArgumentException if the file exists but cannot be read or
     *                                  contains invalid YAML
     */
    private static ClassificationOverride loadClassificationOverride(Path overrideFile) {
        if (overrideFile == null) {
            return ClassificationOverride.empty();
        }
        try {
            return ClassificationOverride.load(overrideFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load override file: " + overrideFile, e);
        }
    }

    /**
     * Converts a single discovered test method into a prompt target descriptor.
     *
     * @param m discovered test method
     * @return corresponding prompt target descriptor; never {@code null}
     * @see PromptBuilder.TargetMethod
     */
    private static PromptBuilder.TargetMethod toTargetMethod(DiscoveredMethod m) {
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
    private static String resolveTaxonomyInfo(AiOptions aiOptions, boolean aiActive) {
        if (!aiActive) {
            return "n/a (AI disabled)";
        }
        if (aiOptions.taxonomyFile() != null) {
            return "file:" + aiOptions.taxonomyFile().toAbsolutePath();
        }
        return "built-in/" + aiOptions.taxonomyMode().name().toLowerCase(Locale.ROOT);
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
    private static TestMethodSink filterSink(TestMethodSink delegate, boolean securityOnly) {
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
     * Loads the AI result cache from the given CSV file, or returns the empty no-op
     * cache when no cache file is configured.
     *
     * @param cacheFile path to a previous MethodAtlas CSV output, or {@code null}
     * @return loaded cache; never {@code null}
     * @throws IllegalArgumentException if the file exists but cannot be read or parsed
     */
    private static AiResultCache buildAiCache(Path cacheFile) {
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
     * Creates the AI suggestion engine for the current run.
     *
     * <p>
     * Returns {@code null} when AI support is disabled. Initialization failures
     * are wrapped in an {@link IllegalStateException}.
     * </p>
     *
     * @param aiOptions AI configuration for the current run
     * @return initialized AI suggestion engine, or {@code null} when AI is
     *         disabled
     * @throws IllegalStateException if engine initialization fails
     */
    private static AiSuggestionEngine buildAiEngine(AiOptions aiOptions) {
        if (!aiOptions.enabled()) {
            return null;
        }

        try {
            return new AiSuggestionEngineImpl(aiOptions);
        } catch (AiSuggestionException e) {
            throw new IllegalStateException("Failed to initialize AI engine", e);
        }
    }

}
