package org.egothor.methodatlas;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

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

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

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
 * the default set ({@link AnnotationInspector#DEFAULT_TEST_ANNOTATIONS})</li>
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
 * @see AnnotationInspector
 * @see OutputEmitter
 * @see SarifEmitter
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

        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(parserConfiguration);

        CliConfig cliConfig = CliArgs.parse(args);
        ClassificationOverride override = loadClassificationOverride(cliConfig.overrideFile());
        AiResultCache aiCache = buildAiCache(cliConfig.aiCacheFile());

        // Manual prepare phase: write AI prompt work files; no CSV output.
        if (cliConfig.manualMode() instanceof ManualMode.Prepare prepare) {
            return runManualPrepare(prepare, cliConfig, parser, out);
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
            return runApplyTagsFromCsv(cliConfig, parser, roots, out);
        }

        // Apply-tags mode: annotate source files; no report emitted.
        if (cliConfig.applyTags()) {
            return runApplyTags(cliConfig, aiEngine, parser, roots, out, override, aiCache);
        }

        // SARIF mode: buffer all records; write JSON once after the scan completes.
        if (cliConfig.outputMode() == OutputMode.SARIF) {
            return runSarif(cliConfig, aiEngine, aiEnabled, confidenceEnabled, parser, roots, out, override, aiCache);
        }

        // GitHub Annotations mode: emit ::notice/::warning workflow commands.
        if (cliConfig.outputMode() == OutputMode.GITHUB_ANNOTATIONS) {
            return runGitHubAnnotations(cliConfig, aiEngine, parser, roots, out, override, aiCache);
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
        boolean hadErrors = false;
        for (Path root : roots) {
            String sourceRoot = emitSourceRoot ? computeFilePrefix(List.of(root)) : null;
            TestMethodSink rootSink = (fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion) ->
                    emitter.emit(mode, fqcn, method, loc, contentHash, tags, displayName, suggestion, sourceRoot);
            if (scanRoot(root, cliConfig.aiOptions(), aiEngine, parser,
                    filterSink(rootSink, cliConfig.securityOnly()),
                    cliConfig.fileSuffixes(), cliConfig.testAnnotations(), cliConfig.contentHash(),
                    override, aiCache)) {
                hadErrors = true;
            }
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
     * @param parser    configured JavaParser instance
     * @param roots     source roots to scan
     * @param log       writer for progress and summary output
     * @return {@code 0} on success, {@code 1} when the mismatch limit is exceeded
     *         or a fatal error occurs
     * @throws IOException if the CSV or source files cannot be read or written
     */
    private static int runApplyTagsFromCsv(CliConfig cliConfig, JavaParser parser,
            List<Path> roots, PrintWriter log) throws IOException {
        return ApplyTagsFromCsvEngine.apply(
                cliConfig.applyTagsFromCsvFile(),
                roots,
                cliConfig.fileSuffixes(),
                cliConfig.testAnnotations(),
                cliConfig.mismatchLimit(),
                parser,
                log);
    }

    /**
     * Applies AI-generated annotations to test method source files.
     *
     * <p>
     * Scans every configured source root, resolves AI suggestions for each
     * discovered test class, and uses {@link TagApplier} to insert
     * {@code @DisplayName} and {@code @Tag} annotations. Each modified file is
     * written back to disk using the lexical-preserving printer so that
     * unrelated formatting is left intact. A summary line is written to
     * {@code log} on completion.
     * </p>
     *
     * @param cliConfig full parsed CLI configuration
     * @param aiEngine  AI engine providing suggestions; may be {@code null}
     * @param parser    configured JavaParser instance
     * @param roots     source roots to scan
     * @param log       writer for progress and summary output
     * @return {@code 0} if all files were processed successfully, {@code 1}
     *         if any file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    private static int runApplyTags(CliConfig cliConfig, AiSuggestionEngine aiEngine,
            JavaParser parser, List<Path> roots, PrintWriter log,
            ClassificationOverride override, AiResultCache aiCache) throws IOException {
        boolean hadErrors = false;
        int modifiedFiles = 0;
        int totalAnnotations = 0;

        for (Path root : roots) {
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> files = stream
                        .filter(path -> cliConfig.fileSuffixes().stream()
                                .anyMatch(s -> path.toString().endsWith(s)))
                        .toList();
                for (Path path : files) {
                    try {
                        int added = applyTagsToFile(root, path, cliConfig, aiEngine, parser, log, override, aiCache);
                        if (added > 0) {
                            modifiedFiles++;
                            totalAnnotations += added;
                        }
                    } catch (IOException e) {
                        if (LOG.isLoggable(Level.WARNING)) {
                            LOG.log(Level.WARNING, "Cannot process: " + path, e);
                        }
                        hadErrors = true;
                    }
                }
            }
        }

        log.println("Apply-tags complete: " + totalAnnotations + " annotation(s) added to "
                + modifiedFiles + " file(s)");
        return hadErrors ? 1 : 0;
    }

    /**
     * Parses a single source file, applies AI-suggested security annotations,
     * and writes the file back when at least one annotation was inserted.
     *
     * <p>
     * {@link LexicalPreservingPrinter} is used so that only the inserted
     * annotations affect the output; all other formatting is preserved.
     * </p>
     *
     * @param root      scan root used to compute the path-based file stem
     * @param path      source file to process
     * @param cliConfig full parsed CLI configuration
     * @param aiEngine  AI engine providing suggestions; may be {@code null}
     * @param parser    configured JavaParser instance
     * @param log       writer for progress output
     * @return number of annotations added to the file
     * @throws IOException if the file cannot be read or written
     */
    private static int applyTagsToFile(Path root, Path path, CliConfig cliConfig,
            AiSuggestionEngine aiEngine, JavaParser parser, PrintWriter log,
            ClassificationOverride override, AiResultCache aiCache) throws IOException {
        ParseResult<CompilationUnit> parseResult = parser.parse(path);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Failed to parse {0}: {1}",
                        new Object[] { path, parseResult.getProblems() });
            }
            return 0;
        }

        CompilationUnit cu = parseResult.getResult().orElseThrow();
        LexicalPreservingPrinter.setup(cu);
        Set<String> effective = AnnotationInspector.effectiveAnnotations(cu, cliConfig.testAnnotations());

        String packageName = cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString).orElse("");

        int displayNamesAdded = 0;
        int tagsAdded = 0;

        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqcn = buildFqcn(packageName, clazz.getNameAsString());
            String fileStem = buildFileStem(root, path, fqcn);
            String lookupHash = aiCache.isActive() ? computeContentHash(clazz) : null;
            List<MethodDeclaration> testMethods = findJUnitTestMethods(clazz, effective);
            SuggestionLookup suggestionLookup = resolveSuggestionLookup(fileStem, clazz, fqcn, testMethods,
                    cliConfig.aiOptions(), aiEngine, override, aiCache, lookupHash);

            TagApplier.ClassResult result = TagApplier.applyToClass(clazz, suggestionLookup,
                    effective);
            displayNamesAdded += result.displayNamesAdded();
            tagsAdded += result.tagsAdded();
        }

        int totalAdded = displayNamesAdded + tagsAdded;
        if (totalAdded > 0) {
            if (displayNamesAdded > 0) {
                cu.addImport(TagApplier.IMPORT_DISPLAY_NAME);
            }
            if (tagsAdded > 0) {
                cu.addImport(TagApplier.IMPORT_TAG);
            }
            Files.writeString(path, LexicalPreservingPrinter.print(cu), StandardCharsets.UTF_8);
            log.println("Modified: " + path + " (+" + totalAdded + " annotation(s))");
        }

        return totalAdded;
    }

    /**
     * Runs the SARIF output path: scans all roots, then serializes the buffered
     * records as a single SARIF document.
     *
     * @param cliConfig         full parsed CLI configuration
     * @param aiEngine          AI engine providing suggestions; may be {@code null}
     * @param aiEnabled         whether an AI engine is active
     * @param confidenceEnabled whether the {@code aiConfidence} property should be
     *                          included in SARIF properties
     * @param parser            configured JavaParser instance
     * @param roots             source roots to scan
     * @param out               writer that receives the serialized SARIF document
     * @return {@code 0} if all files were processed successfully, {@code 1} if any
     *         file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    private static int runSarif(CliConfig cliConfig, AiSuggestionEngine aiEngine,
            boolean aiEnabled, boolean confidenceEnabled,
            JavaParser parser, List<Path> roots, PrintWriter out,
            ClassificationOverride override, AiResultCache aiCache) throws IOException {
        String filePrefix = computeFilePrefix(roots);
        SarifEmitter sarifEmitter = new SarifEmitter(aiEnabled, confidenceEnabled, filePrefix);
        int result = scan(roots, cliConfig, aiEngine, parser,
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
     * @param parser    configured JavaParser instance
     * @param roots     source roots to scan
     * @param out       writer that receives the workflow command lines
     * @return {@code 0} if all files were processed successfully, {@code 1} if any
     *         file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    private static int runGitHubAnnotations(CliConfig cliConfig, AiSuggestionEngine aiEngine,
            JavaParser parser, List<Path> roots, PrintWriter out,
            ClassificationOverride override, AiResultCache aiCache) throws IOException {
        String filePrefix = computeFilePrefix(roots);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, filePrefix);
        return scan(roots, cliConfig, aiEngine, parser, emitter, override, aiCache);
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
     * @param parser    configured JavaParser instance
     * @param sink      receiver of discovered test method records
     * @return {@code 0} if all files were processed successfully, {@code 1} if any
     *         file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    private static int scan(List<Path> roots, CliConfig cliConfig, AiSuggestionEngine aiEngine,
            JavaParser parser, TestMethodSink sink, ClassificationOverride override,
            AiResultCache aiCache) throws IOException {
        boolean hadErrors = false;
        for (Path root : roots) {
            if (scanRoot(root, cliConfig.aiOptions(), aiEngine, parser, sink,
                    cliConfig.fileSuffixes(), cliConfig.testAnnotations(), cliConfig.contentHash(), override,
                    aiCache)) {
                hadErrors = true;
            }
        }
        return hadErrors ? 1 : 0;
    }

    /**
     * Executes the manual AI prepare phase.
     *
     * <p>
     * Scans the configured source roots, discovers test classes and their JUnit
     * test methods, and writes one work file per class to the prepare work
     * directory. Progress lines are written to {@code log}. No CSV output is
     * produced.
     * </p>
     *
     * @param prepare    manual prepare mode configuration
     * @param cliConfig  full parsed CLI configuration (used for paths, suffix,
     *                   and taxonomy options)
     * @param parser     configured JavaParser instance
     * @param log        writer used for progress reporting
     * @return {@code 0} if all files were processed successfully, {@code 1} if any
     *         file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    private static int runManualPrepare(ManualMode.Prepare prepare, CliConfig cliConfig, JavaParser parser,
            PrintWriter log) throws IOException {
        ManualPrepareEngine engine;
        try {
            engine = new ManualPrepareEngine(prepare.workDir(), prepare.responseDir(), cliConfig.aiOptions());
        } catch (AiSuggestionException e) {
            throw new IllegalStateException("Failed to initialize manual prepare engine", e);
        }

        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();
        boolean hadErrors = false;
        int prepared = 0;

        for (Path root : roots) {
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> files = stream
                        .filter(path -> cliConfig.fileSuffixes().stream()
                                .anyMatch(s -> path.toString().endsWith(s)))
                        .toList();
                for (Path path : files) {
                    int count = processFileForPrepare(root, path, engine, parser, log, cliConfig.testAnnotations());
                    if (count < 0) {
                        hadErrors = true;
                    } else {
                        prepared += count;
                    }
                }
            }
        }

        log.println("Manual prepare complete. Wrote " + prepared + " work file(s) to " + prepare.workDir()
                + " (response stubs in " + prepare.responseDir() + ")");
        return hadErrors ? 1 : 0;
    }

    /**
     * Parses a single Java source file and writes work files for each discovered
     * test class.
     *
     * @param root   scan root used to compute the path-based file stem
     * @param path   source file to parse
     * @param engine prepare engine used to write work files
     * @param parser configured JavaParser instance
     * @param log    writer used for progress reporting
     * @param testAnnotations set of annotation simple names that identify test
     *                        methods
     * @return number of work files written, or {@code -1} if the file could not
     *         be parsed
     */
    private static int processFileForPrepare(Path root, Path path, ManualPrepareEngine engine, JavaParser parser,
            PrintWriter log, Set<String> testAnnotations) {
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(path);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING, "Failed to parse {0}: {1}",
                            new Object[] { path, parseResult.getProblems() });
                }
                return -1;
            }

            CompilationUnit compilationUnit = parseResult.getResult().orElseThrow();
            Set<String> effective = AnnotationInspector.effectiveAnnotations(compilationUnit, testAnnotations);
            String packageName = compilationUnit.getPackageDeclaration()
                    .map(NodeWithName::getNameAsString).orElse("");

            int count = 0;
            for (ClassOrInterfaceDeclaration clazz : compilationUnit
                    .findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = buildFqcn(packageName, clazz.getNameAsString());
                List<MethodDeclaration> testMethods = findJUnitTestMethods(clazz, effective);

                if (testMethods.isEmpty()) {
                    continue;
                }

                String fileStem = buildFileStem(root, path, fqcn);
                List<PromptBuilder.TargetMethod> targetMethods = toTargetMethods(testMethods);
                try {
                    Path workFile = engine.prepare(fileStem, fqcn, clazz.toString(), targetMethods);
                    log.println("Prepared: " + workFile);
                    count++;
                } catch (AiSuggestionException e) {
                    if (LOG.isLoggable(Level.WARNING)) {
                        LOG.log(Level.WARNING, "Failed to prepare work file for " + fqcn, e);
                    }
                }
            }

            return count;

        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Cannot read file: " + path, e);
            }
            return -1;
        }
    }

    /**
     * Recursively scans a directory tree for Java test source files.
     *
     * @param root       root directory to scan
     * @param aiOptions  AI configuration for the current run
     * @param aiEngine   AI engine, or {@code null} when AI is disabled
     * @param parser     configured JavaParser instance
     * @param sink       receiver of discovered test method records
     * @param fileSuffixes    one or more filename suffixes used to select source
     *                        files; a file is included if its name ends with any of
     *                        the listed suffixes
     * @param testAnnotations set of annotation simple names that identify test
     *                        methods
     * @return {@code true} if any file produced a processing error
     * @throws IOException if traversing the file tree fails
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static boolean scanRoot(Path root, AiOptions aiOptions, AiSuggestionEngine aiEngine,
            JavaParser parser, TestMethodSink sink, List<String> fileSuffixes,
            Set<String> testAnnotations, boolean contentHashEnabled,
            ClassificationOverride override, AiResultCache aiCache) throws IOException {
        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "Scanning {0} for files matching {1}", new Object[] { root, fileSuffixes });
        }

        boolean hadErrors = false;

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(path -> fileSuffixes.stream().anyMatch(s -> path.toString().endsWith(s)))
                    .toList();
            for (Path path : files) {
                if (!processFile(root, path, aiOptions, aiEngine, parser, sink, testAnnotations,
                        contentHashEnabled, override, aiCache)) {
                    hadErrors = true;
                }
            }
        }

        return hadErrors;
    }

    /**
     * Parses a single Java source file, discovers JUnit test methods, and forwards
     * each to the supplied sink.
     *
     * @param root              scan root used to compute the path-based file stem
     * @param path              source file to parse
     * @param aiOptions         AI configuration for the current run
     * @param aiEngine          AI engine, or {@code null} when AI is disabled
     * @param parser            configured JavaParser instance
     * @param sink              receiver of discovered test method records
     * @param testAnnotations   set of annotation simple names that identify test
     *                          methods
     * @param contentHashEnabled whether to compute a SHA-256 fingerprint of each
     *                          class source and include it in the records
     * @return {@code true} if the file was processed successfully
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static boolean processFile(Path root, Path path, AiOptions aiOptions,
            AiSuggestionEngine aiEngine, JavaParser parser, TestMethodSink sink,
            Set<String> testAnnotations, boolean contentHashEnabled, ClassificationOverride override,
            AiResultCache aiCache) {
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(path);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING, "Failed to parse {0}: {1}",
                            new Object[] { path, parseResult.getProblems() });
                }
                return false;
            }

            CompilationUnit compilationUnit = parseResult.getResult().orElseThrow();
            Set<String> effective = AnnotationInspector.effectiveAnnotations(compilationUnit, testAnnotations);
            String packageName = compilationUnit.getPackageDeclaration()
                    .map(NodeWithName::getNameAsString).orElse("");

            compilationUnit.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String fqcn = buildFqcn(packageName, clazz.getNameAsString());
                String fileStem = buildFileStem(root, path, fqcn);
                // Compute hash when needed for output OR for cache lookup.
                String lookupHash = (contentHashEnabled || aiCache.isActive())
                        ? computeContentHash(clazz) : null;
                String outputHash = contentHashEnabled ? lookupHash : null;

                List<MethodDeclaration> testMethods = findJUnitTestMethods(clazz, effective);
                SuggestionLookup suggestionLookup = resolveSuggestionLookup(fileStem, clazz, fqcn, testMethods,
                        aiOptions, aiEngine, override, aiCache, lookupHash);

                for (MethodDeclaration method : testMethods) {
                    int beginLine = method.getRange().map(range -> range.begin.line).orElse(0);
                    int loc = AnnotationInspector.countLOC(method);
                    List<String> tags = AnnotationInspector.getTagValues(method);
                    String displayName = AnnotationInspector.getDisplayName(method);
                    sink.record(fqcn, method.getNameAsString(), beginLine, loc, outputHash, tags,
                            displayName, suggestionLookup.find(method.getNameAsString()).orElse(null));
                }
            });

            return true;

        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Cannot read file: " + path, e);
            }
            return false;
        }
    }

    /**
     * Builds a fully qualified class name from a package name and a simple class
     * name.
     *
     * @param packageName package name; may be empty for the default package
     * @param className   simple class name
     * @return fully qualified class name
     */
    private static String buildFqcn(String packageName, String className) {
        return packageName.isEmpty() ? className : packageName + "." + className;
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
     * Computes a SHA-256 content fingerprint of a class declaration.
     *
     * <p>
     * The hash is derived from the JavaParser pretty-printed form of the class
     * declaration, which normalizes whitespace so that insignificant formatting
     * changes do not alter the fingerprint. The result is a 64-character
     * lowercase hexadecimal string.
     * </p>
     *
     * @param clazz parsed class declaration to fingerprint
     * @return 64-character lowercase hex SHA-256 digest
     * @throws IllegalStateException if SHA-256 is unavailable (never in practice;
     *                               SHA-256 is mandated by the Java SE spec)
     */
    private static String computeContentHash(ClassOrInterfaceDeclaration clazz) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(clazz.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Returns all JUnit test methods declared within the specified class.
     *
     * @param clazz parsed class declaration whose methods should be inspected
     * @return list of JUnit test method declarations; possibly empty but never
     *         {@code null}
     * @param testAnnotations set of annotation simple names to match
     * @see AnnotationInspector#isJUnitTest(MethodDeclaration, Set)
     */
    private static List<MethodDeclaration> findJUnitTestMethods(ClassOrInterfaceDeclaration clazz,
            Set<String> testAnnotations) {
        return clazz.findAll(MethodDeclaration.class).stream()
                .filter(m -> AnnotationInspector.isJUnitTest(m, testAnnotations)).toList();
    }

    /**
     * Resolves method-level AI suggestions for a parsed class.
     *
     * <p>
     * Returns an empty lookup when no AI engine is available, the method list is
     * empty, or (for regular provider-based AI) the class source exceeds the
     * configured maximum size. The {@code maxClassChars} limit is only enforced
     * when the automated provider is enabled ({@link AiOptions#enabled()}); it is
     * not applied in the manual consume phase.
     * </p>
     *
     * @param fileStem    dot-separated path stem identifying the source file;
     *                    forwarded to {@link AiSuggestionEngine#suggestForClass}
     * @param clazz       parsed class declaration to analyze
     * @param fqcn        fully qualified class name of {@code clazz}
     * @param testMethods discovered JUnit test methods
     * @param aiOptions   AI configuration for the current run
     * @param aiEngine    AI engine used to produce suggestions; {@code null} when
     *                    AI is disabled
     * @param override    human classification overrides to apply after AI results;
     *                    use {@link ClassificationOverride#empty()} when no override
     *                    file is configured
     * @return lookup of AI suggestions keyed by method name; never {@code null}
     */
    private static SuggestionLookup resolveSuggestionLookup(String fileStem, ClassOrInterfaceDeclaration clazz,
            String fqcn, List<MethodDeclaration> testMethods, AiOptions aiOptions, AiSuggestionEngine aiEngine,
            ClassificationOverride override, AiResultCache aiCache, String contentHash) {
        if (testMethods.isEmpty()) {
            return SuggestionLookup.from(null);
        }

        List<String> methodNames = testMethods.stream().map(MethodDeclaration::getNameAsString).toList();

        if (aiEngine == null) {
            return SuggestionLookup.from(override.apply(fqcn, null, methodNames));
        }

        // Check the cache before making an API call.
        AiClassSuggestion cached = aiCache.lookup(contentHash).orElse(null);
        if (cached != null) {
            return SuggestionLookup.from(override.apply(fqcn, cached, methodNames));
        }

        String classSource = clazz.toString();
        if (aiOptions.enabled() && classSource.length() > aiOptions.maxClassChars()) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.log(Level.INFO, "Skipping AI for {0}: class source too large ({1} chars)",
                        new Object[] { fqcn, classSource.length() });
            }
            return SuggestionLookup.from(override.apply(fqcn, null, methodNames));
        }

        List<PromptBuilder.TargetMethod> targetMethods = toTargetMethods(testMethods);

        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "Querying AI for {0} ({1} methods)", new Object[] { fqcn, targetMethods.size() });
        }

        try {
            AiClassSuggestion aiClassSuggestion = aiEngine.suggestForClass(fileStem, fqcn, classSource, targetMethods);
            return SuggestionLookup.from(override.apply(fqcn, aiClassSuggestion, methodNames));
        } catch (AiSuggestionException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "AI suggestion failed for class " + fqcn, e);
            }
            return SuggestionLookup.from(override.apply(fqcn, null, methodNames));
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
     * Converts parsed JUnit test method declarations into prompt target descriptors.
     *
     * @param testMethods list of parsed JUnit test method declarations
     * @return list of prompt target descriptors; possibly empty but never
     *         {@code null}
     * @see PromptBuilder.TargetMethod
     */
    private static List<PromptBuilder.TargetMethod> toTargetMethods(List<MethodDeclaration> testMethods) {
        return testMethods.stream()
                .map(method -> new PromptBuilder.TargetMethod(method.getNameAsString(),
                        method.getRange().map(range -> range.begin.line).orElse(null),
                        method.getRange().map(range -> range.end.line).orElse(null)))
                .toList();
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
