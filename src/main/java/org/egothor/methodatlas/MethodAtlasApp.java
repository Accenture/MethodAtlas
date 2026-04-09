package org.egothor.methodatlas;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiProvider;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.egothor.methodatlas.ai.AiSuggestionException;
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
 * (default: {@code Test.java})</li>
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
public class MethodAtlasApp {

    private static final Logger LOG = Logger.getLogger(MethodAtlasApp.class.getName());
    private static final String DEFAULT_FILE_SUFFIX = "Test.java";

    /**
     * Parsed command-line configuration used to drive a single application run.
     *
     * @param outputMode selected output mode
     * @param aiOptions  AI configuration controlling provider selection, taxonomy,
     *                   limits, and timeouts
     * @param paths      root paths to scan; when empty, the current working
     *                   directory is scanned
     * @param fileSuffix filename suffix used to select source files for scanning
     */
    private record CliConfig(OutputMode outputMode, AiOptions aiOptions, List<Path> paths, String fileSuffix) {
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
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        int exitCode = run(args, out);
        out.flush();
        if (exitCode != 0) {
            System.exit(exitCode); // NOPMD - CLI application exit code
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
    static int run(String[] args, PrintWriter out) throws IOException {
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(parserConfiguration);

        CliConfig cliConfig = parseArgs(args);
        AiSuggestionEngine aiEngine = buildAiEngine(cliConfig.aiOptions());
        OutputEmitter emitter = new OutputEmitter(out, cliConfig.aiOptions().enabled());

        emitter.emitCsvHeader(cliConfig.outputMode());

        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();
        boolean hadErrors = false;

        for (Path root : roots) {
            if (scanRoot(root, cliConfig.outputMode(), cliConfig.aiOptions(), aiEngine, parser, emitter,
                    cliConfig.fileSuffix())) {
                hadErrors = true;
            }
        }

        return hadErrors ? 1 : 0;
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
     * @param fileSuffix filename suffix used to select source files
     * @return {@code true} if any file produced a processing error
     * @throws IOException if traversing the file tree fails
     */
    private static boolean scanRoot(Path root, OutputMode mode, AiOptions aiOptions, AiSuggestionEngine aiEngine,
            JavaParser parser, OutputEmitter emitter, String fileSuffix) throws IOException {
        LOG.log(Level.INFO, "Scanning {0} for files matching *{1}", new Object[] { root, fileSuffix });

        boolean hadErrors = false;

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream.filter(path -> path.toString().endsWith(fileSuffix)).toList();
            for (Path path : files) {
                if (!processFile(path, mode, aiOptions, aiEngine, parser, emitter)) {
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
     * @param emitter   output emitter for the current run
     * @return {@code true} if the file was processed successfully
     */
    private static boolean processFile(Path path, OutputMode mode, AiOptions aiOptions, AiSuggestionEngine aiEngine,
            JavaParser parser, OutputEmitter emitter) {
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
                String className = clazz.getNameAsString();
                String fqcn = packageName.isEmpty() ? className : packageName + "." + className;

                List<MethodDeclaration> testMethods = findJUnitTestMethods(clazz);
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
     * Returns all JUnit test methods declared within the specified class.
     *
     * @param clazz parsed class declaration whose methods should be inspected
     * @return list of JUnit test method declarations; possibly empty but never
     *         {@code null}
     * @see AnnotationInspector#isJUnitTest(MethodDeclaration)
     */
    private static List<MethodDeclaration> findJUnitTestMethods(ClassOrInterfaceDeclaration clazz) {
        return clazz.findAll(MethodDeclaration.class).stream()
                .filter(AnnotationInspector::isJUnitTest).toList();
    }

    /**
     * Resolves method-level AI suggestions for a parsed class.
     *
     * <p>
     * Returns an empty lookup when AI is disabled, no engine is available, the
     * method list is empty, the class source exceeds the configured maximum size,
     * or the AI engine fails.
     * </p>
     *
     * @param clazz       parsed class declaration to analyze
     * @param fqcn        fully qualified class name of {@code clazz}
     * @param testMethods discovered JUnit test methods
     * @param aiOptions   AI configuration for the current run
     * @param aiEngine    AI engine used to produce suggestions
     * @return lookup of AI suggestions keyed by method name; never {@code null}
     */
    private static SuggestionLookup resolveSuggestionLookup(ClassOrInterfaceDeclaration clazz, String fqcn,
            List<MethodDeclaration> testMethods, AiOptions aiOptions, AiSuggestionEngine aiEngine) {
        if (!aiOptions.enabled() || aiEngine == null || testMethods.isEmpty()) {
            return SuggestionLookup.from(null);
        }

        String classSource = clazz.toString();
        if (classSource.length() > aiOptions.maxClassChars()) {
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
        String fileSuffix = DEFAULT_FILE_SUFFIX;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "-plain" -> outputMode = OutputMode.PLAIN;
                case "-ai" -> aiBuilder.enabled(true);
                case "-ai-provider" ->
                    aiBuilder.provider(AiProvider.valueOf(nextArg(args, ++i, arg).toUpperCase(Locale.ROOT)));
                case "-ai-model" -> aiBuilder.modelName(nextArg(args, ++i, arg));
                case "-ai-base-url" -> aiBuilder.baseUrl(nextArg(args, ++i, arg));
                case "-ai-api-key" -> aiBuilder.apiKey(nextArg(args, ++i, arg));
                case "-ai-api-key-env" -> aiBuilder.apiKeyEnv(nextArg(args, ++i, arg));
                case "-ai-taxonomy" -> aiBuilder.taxonomyFile(Paths.get(nextArg(args, ++i, arg)));
                case "-ai-taxonomy-mode" -> aiBuilder
                        .taxonomyMode(AiOptions.TaxonomyMode.valueOf(nextArg(args, ++i, arg).toUpperCase(Locale.ROOT)));
                case "-ai-max-class-chars" -> aiBuilder.maxClassChars(Integer.parseInt(nextArg(args, ++i, arg)));
                case "-ai-timeout-sec" ->
                    aiBuilder.timeout(Duration.ofSeconds(Long.parseLong(nextArg(args, ++i, arg))));
                case "-ai-max-retries" -> aiBuilder.maxRetries(Integer.parseInt(nextArg(args, ++i, arg)));
                case "-file-suffix" -> fileSuffix = nextArg(args, ++i, arg);
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                    }
                    paths.add(Paths.get(arg));
                }
            }
        }

        return new CliConfig(outputMode, aiBuilder.build(), paths, fileSuffix);
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
