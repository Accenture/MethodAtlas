package org.egothor.methodatlas;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiProvider;
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
 * operator-saved AI response files ({@code <fqcn>.response.txt}) from the
 * response directory and produces the final enriched CSV. Classes whose
 * response file is absent receive empty AI columns.</li>
 * </ol>
 *
 * <h2>Supported Command-Line Options</h2>
 *
 * <ul>
 * <li>{@code -plain} — emits plain text output instead of CSV</li>
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
 * <li>{@code -manual-prepare <workdir> <responsedir>} — runs the manual AI
 * prepare phase, writing work files to {@code workdir} and empty response stubs
 * to {@code responsedir}; the two paths may be identical</li>
 * <li>{@code -manual-consume <workdir> <responsedir>} — runs the manual AI
 * consume phase, reading response files from {@code responsedir} and emitting
 * the final enriched CSV</li>
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
 * @see #main(String[])
 */
public final class MethodAtlasApp {

    private static final Logger LOG = Logger.getLogger(MethodAtlasApp.class.getName());
    private static final String DEFAULT_FILE_SUFFIX = "Test.java";

    /**
     * Prevents instantiation of this utility class.
     */
    private MethodAtlasApp() {
    }

    /**
     * Selects between the two phases of the manual AI workflow.
     *
     * <p>
     * When a {@code ManualMode} is present in the parsed configuration the
     * application bypasses the normal automated AI provider path.
     * </p>
     */
    private sealed interface ManualMode {
        /**
         * Prepare phase: scan source files and write AI prompt work files and empty
         * response stubs.
         *
         * @param workDir     directory where work files ({@code <fqcn>.txt}) will be
         *                    written
         * @param responseDir directory where empty response stubs
         *                    ({@code <fqcn>.response.txt}) will be pre-created; may be
         *                    the same as {@code workDir}
         */
        record Prepare(Path workDir, Path responseDir) implements ManualMode {
        }

        /**
         * Consume phase: read operator-saved response files and emit enriched CSV.
         *
         * @param workDir     directory that contains the work files written during
         *                    prepare (reserved for future reference; currently unused
         *                    at runtime)
         * @param responseDir directory where the operator saved
         *                    {@code <fqcn>.response.txt} files
         */
        record Consume(Path workDir, Path responseDir) implements ManualMode {
        }
    }

    /**
     * Parsed command-line configuration used to drive a single application run.
     *
     * @param outputMode selected output mode
     * @param aiOptions  AI configuration controlling provider selection, taxonomy,
     *                   limits, and timeouts
     * @param paths      root paths to scan; when empty, the current working
     *                   directory is scanned
     * @param fileSuffixes    one or more filename suffixes used to select source
     *                        files for scanning; a file is included if its name
     *                        ends with any of the listed suffixes
     * @param testAnnotations set of annotation simple names used to identify test
     *                        methods; defaults to
     *                        {@link AnnotationInspector#DEFAULT_TEST_ANNOTATIONS}
     * @param emitMetadata    whether to emit {@code # key: value} metadata comment
     *                        lines before the CSV header
     * @param manualMode      manual AI workflow mode, or {@code null} when using
     *                        automated providers
     */
    private record CliConfig(OutputMode outputMode, AiOptions aiOptions, List<Path> paths, List<String> fileSuffixes,
            Set<String> testAnnotations, boolean emitMetadata, ManualMode manualMode) {
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
        int exitCode;
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)) {
            exitCode = run(args, out);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
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
    /* default */ static int run(String[] args, PrintWriter out) throws IOException {
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(parserConfiguration);

        CliConfig cliConfig = parseArgs(args);

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

        OutputEmitter emitter = new OutputEmitter(out, aiEngine != null);

        if (cliConfig.emitMetadata()) {
            String version = MethodAtlasApp.class.getPackage().getImplementationVersion();
            String taxonomyInfo = resolveTaxonomyInfo(cliConfig.aiOptions(), aiEngine != null);
            emitter.emitMetadata(version != null ? version : "dev", Instant.now().toString(), taxonomyInfo);
        }

        emitter.emitCsvHeader(cliConfig.outputMode());

        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();
        boolean hadErrors = false;

        for (Path root : roots) {
            if (scanRoot(root, cliConfig.outputMode(), cliConfig.aiOptions(), aiEngine, parser, emitter,
                    cliConfig.fileSuffixes(), cliConfig.testAnnotations())) {
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
                    int count = processFileForPrepare(path, engine, parser, log, cliConfig.testAnnotations());
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
     * @param path   source file to parse
     * @param engine prepare engine used to write work files
     * @param parser configured JavaParser instance
     * @param log    writer used for progress reporting
     * @param testAnnotations set of annotation simple names that identify test
     *                        methods
     * @return number of work files written, or {@code -1} if the file could not
     *         be parsed
     */
    private static int processFileForPrepare(Path path, ManualPrepareEngine engine, JavaParser parser,
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
            String packageName = compilationUnit.getPackageDeclaration()
                    .map(NodeWithName::getNameAsString).orElse("");

            int count = 0;
            for (ClassOrInterfaceDeclaration clazz : compilationUnit
                    .findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = buildFqcn(packageName, clazz.getNameAsString());
                List<MethodDeclaration> testMethods = findJUnitTestMethods(clazz, testAnnotations);

                if (testMethods.isEmpty()) {
                    continue;
                }

                List<PromptBuilder.TargetMethod> targetMethods = toTargetMethods(testMethods);
                try {
                    Path workFile = engine.prepare(fqcn, clazz.toString(), targetMethods);
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
     * @param mode       output mode used for emitted records
     * @param aiOptions  AI configuration for the current run
     * @param aiEngine   AI engine, or {@code null} when AI is disabled
     * @param parser     configured JavaParser instance
     * @param emitter    output emitter for the current run
     * @param fileSuffixes    one or more filename suffixes used to select source
     *                        files; a file is included if its name ends with any of
     *                        the listed suffixes
     * @param testAnnotations set of annotation simple names that identify test
     *                        methods
     * @return {@code true} if any file produced a processing error
     * @throws IOException if traversing the file tree fails
     */
    private static boolean scanRoot(Path root, OutputMode mode, AiOptions aiOptions, AiSuggestionEngine aiEngine,
            JavaParser parser, OutputEmitter emitter, List<String> fileSuffixes,
            Set<String> testAnnotations) throws IOException {
        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "Scanning {0} for files matching {1}", new Object[] { root, fileSuffixes });
        }

        boolean hadErrors = false;

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(path -> fileSuffixes.stream().anyMatch(s -> path.toString().endsWith(s)))
                    .toList();
            for (Path path : files) {
                if (!processFile(path, mode, aiOptions, aiEngine, parser, emitter, testAnnotations)) {
                    hadErrors = true;
                }
            }
        }

        return hadErrors;
    }

    /**
     * Parses a single Java source file, discovers JUnit test methods, and emits
     * output records for each discovered method.
     *
     * @param path      source file to parse
     * @param mode      output mode used for emitted records
     * @param aiOptions AI configuration for the current run
     * @param aiEngine  AI engine, or {@code null} when AI is disabled
     * @param parser    configured JavaParser instance
     * @param emitter         output emitter for the current run
     * @param testAnnotations set of annotation simple names that identify test
     *                        methods
     * @return {@code true} if the file was processed successfully
     */
    private static boolean processFile(Path path, OutputMode mode, AiOptions aiOptions, AiSuggestionEngine aiEngine,
            JavaParser parser, OutputEmitter emitter, Set<String> testAnnotations) {
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
            String packageName = compilationUnit.getPackageDeclaration()
                    .map(NodeWithName::getNameAsString).orElse("");

            compilationUnit.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String fqcn = buildFqcn(packageName, clazz.getNameAsString());

                List<MethodDeclaration> testMethods = findJUnitTestMethods(clazz, testAnnotations);
                SuggestionLookup suggestionLookup = resolveSuggestionLookup(clazz, fqcn, testMethods, aiOptions,
                        aiEngine);

                for (MethodDeclaration method : testMethods) {
                    int loc = AnnotationInspector.countLOC(method);
                    List<String> tags = AnnotationInspector.getTagValues(method);
                    emitter.emit(mode, fqcn, method.getNameAsString(), loc, tags,
                            suggestionLookup.find(method.getNameAsString()).orElse(null));
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
     * @param clazz       parsed class declaration to analyze
     * @param fqcn        fully qualified class name of {@code clazz}
     * @param testMethods discovered JUnit test methods
     * @param aiOptions   AI configuration for the current run
     * @param aiEngine    AI engine used to produce suggestions; {@code null} when
     *                    AI is disabled
     * @return lookup of AI suggestions keyed by method name; never {@code null}
     */
    private static SuggestionLookup resolveSuggestionLookup(ClassOrInterfaceDeclaration clazz, String fqcn,
            List<MethodDeclaration> testMethods, AiOptions aiOptions, AiSuggestionEngine aiEngine) {
        if (aiEngine == null || testMethods.isEmpty()) {
            return SuggestionLookup.from(null);
        }

        String classSource = clazz.toString();
        if (aiOptions.enabled() && classSource.length() > aiOptions.maxClassChars()) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.log(Level.INFO, "Skipping AI for {0}: class source too large ({1} chars)",
                        new Object[] { fqcn, classSource.length() });
            }
            return SuggestionLookup.from(null);
        }

        List<PromptBuilder.TargetMethod> targetMethods = toTargetMethods(testMethods);

        try {
            AiClassSuggestion aiClassSuggestion = aiEngine.suggestForClass(fqcn, classSource, targetMethods);
            return SuggestionLookup.from(aiClassSuggestion);
        } catch (AiSuggestionException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "AI suggestion failed for class " + fqcn, e);
            }
            return SuggestionLookup.from(null);
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

    /**
     * Parses command-line arguments into a structured configuration object.
     *
     * @param args raw command-line arguments
     * @return parsed command-line configuration
     * @throws IllegalArgumentException if an option value is missing, malformed,
     *                                  or unsupported
     */
    @SuppressWarnings("PMD.AvoidReassigningLoopVariables")
    private static CliConfig parseArgs(String... args) {
        OutputMode outputMode = OutputMode.CSV;
        List<Path> paths = new ArrayList<>();
        AiOptions.Builder aiBuilder = AiOptions.builder();
        List<String> fileSuffixes = new ArrayList<>();
        Set<String> testAnnotations = new LinkedHashSet<>();
        boolean emitMetadata = false;
        String manualWorkDir = null;
        String manualResponseDir = null;
        boolean manualIsConsume = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-ai")) {
                i = applyAiArg(arg, args, i, aiBuilder);
                continue;
            }
            switch (arg) {
                case "-plain" -> outputMode = OutputMode.PLAIN;
                case "-file-suffix" -> fileSuffixes.add(nextArg(args, ++i, arg));
                case "-test-annotation" -> testAnnotations.add(nextArg(args, ++i, arg));
                case "-emit-metadata" -> emitMetadata = true;
                case "-manual-prepare" -> {
                    manualWorkDir = nextArg(args, ++i, arg);
                    manualResponseDir = nextArg(args, ++i, arg);
                    manualIsConsume = false;
                }
                case "-manual-consume" -> {
                    manualWorkDir = nextArg(args, ++i, arg);
                    manualResponseDir = nextArg(args, ++i, arg);
                    manualIsConsume = true;
                }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                    }
                    paths.add(Paths.get(arg));
                }
            }
        }

        ManualMode manualMode = null;
        if (manualWorkDir != null) {
            Path workDir = Paths.get(manualWorkDir);
            Path responseDir = Paths.get(manualResponseDir);
            manualMode = manualIsConsume
                    ? new ManualMode.Consume(workDir, responseDir)
                    : new ManualMode.Prepare(workDir, responseDir);
        }

        List<String> resolvedSuffixes = fileSuffixes.isEmpty() ? List.of(DEFAULT_FILE_SUFFIX) : fileSuffixes;
        Set<String> resolvedAnnotations = testAnnotations.isEmpty()
                ? AnnotationInspector.DEFAULT_TEST_ANNOTATIONS : testAnnotations;
        return new CliConfig(outputMode, aiBuilder.build(), paths, resolvedSuffixes, resolvedAnnotations,
                emitMetadata, manualMode);
    }

    /**
     * Applies a single AI-related command-line argument to the builder.
     *
     * <p>
     * Handles all {@code -ai*} flags. Returns the updated argument index so
     * the caller's loop counter stays consistent when the flag consumes an
     * additional value token.
     * </p>
     *
     * @param arg     the flag token being processed
     * @param args    full argument array
     * @param i       current position in {@code args}
     * @param builder AI options builder to update
     * @return updated value of {@code i} after consuming any argument value
     * @throws IllegalArgumentException if a required value token is missing
     */
    private static int applyAiArg(String arg, String[] args, int i, AiOptions.Builder builder) {
        int idx = i;
        switch (arg) {
            case "-ai" -> builder.enabled(true);
            case "-ai-provider" ->
                builder.provider(AiProvider.valueOf(nextArg(args, ++idx, arg).toUpperCase(Locale.ROOT)));
            case "-ai-model" -> builder.modelName(nextArg(args, ++idx, arg));
            case "-ai-base-url" -> builder.baseUrl(nextArg(args, ++idx, arg));
            case "-ai-api-key" -> builder.apiKey(nextArg(args, ++idx, arg));
            case "-ai-api-key-env" -> builder.apiKeyEnv(nextArg(args, ++idx, arg));
            case "-ai-taxonomy" -> builder.taxonomyFile(Paths.get(nextArg(args, ++idx, arg)));
            case "-ai-taxonomy-mode" ->
                builder.taxonomyMode(AiOptions.TaxonomyMode.valueOf(nextArg(args, ++idx, arg).toUpperCase(Locale.ROOT)));
            case "-ai-max-class-chars" -> builder.maxClassChars(Integer.parseInt(nextArg(args, ++idx, arg)));
            case "-ai-timeout-sec" -> builder.timeout(Duration.ofSeconds(Long.parseLong(nextArg(args, ++idx, arg))));
            case "-ai-max-retries" -> builder.maxRetries(Integer.parseInt(nextArg(args, ++idx, arg)));
            default -> throw new IllegalArgumentException("Unknown AI argument: " + arg);
        }
        return idx;
    }

    /**
     * Returns the argument value following an option token.
     *
     * @param args   full command-line argument array
     * @param index  index of the expected option value
     * @param option option whose value is being retrieved
     * @return argument value at {@code index}
     * @throws IllegalArgumentException if {@code index} is outside the bounds of
     *                                  {@code args}
     */
    private static String nextArg(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
