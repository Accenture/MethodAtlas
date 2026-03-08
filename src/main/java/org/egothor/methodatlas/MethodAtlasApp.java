package org.egothor.methodatlas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiProvider;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.egothor.methodatlas.ai.AiSuggestionException;
import org.egothor.methodatlas.ai.SuggestionLookup;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;

/**
 * Command-line application for scanning Java test sources, extracting JUnit
 * test metadata, and optionally enriching the emitted results with AI-generated
 * security tagging suggestions.
 *
 * <p>
 * The application traverses one or more directory roots, parses matching source
 * files using JavaParser, identifies supported JUnit Jupiter test methods, and
 * emits one output record per discovered test method. The current file
 * selection strategy includes only source files whose names end with
 * {@code Test.java}.
 * </p>
 *
 * <h2>Source-Derived Metadata</h2>
 *
 * <p>
 * For each discovered test method, the application reports source-derived
 * metadata including:
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
 * the returned method-level suggestions into the emitted output. Depending on
 * the configured provider and taxonomy, these suggestions may include:
 * </p>
 * <ul>
 * <li>whether a test method is considered security-relevant</li>
 * <li>a suggested security-oriented display name</li>
 * <li>taxonomy-based security tags</li>
 * <li>an explanatory rationale</li>
 * </ul>
 *
 * <h2>Supported Command-Line Options</h2>
 *
 * <p>
 * The application recognizes the following principal command-line options:
 * </p>
 * <ul>
 * <li>{@code -plain} — emits plain text output instead of CSV</li>
 * <li>{@code -ai} — enables AI-based enrichment of emitted method records</li>
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
 * </ul>
 *
 * <p>
 * Any remaining non-option arguments are interpreted as root paths to scan. If
 * no scan path is supplied, the current working directory is scanned.
 * </p>
 *
 * <h2>Output Modes</h2>
 *
 * <p>
 * The application supports two output modes:
 * </p>
 * <ul>
 * <li><b>CSV</b> (default)</li>
 * <li><b>Plain text</b>, enabled by {@code -plain}</li>
 * </ul>
 *
 * <p>
 * In CSV mode, the emitted header is:
 * </p>
 * <pre>{@code
 * fqcn,method,loc,tags
 * }</pre>
 *
 * <p>
 * When AI support is enabled, the emitted CSV header becomes:
 * </p>
 * <pre>{@code
 * fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason
 * }</pre>
 *
 * <h2>Typical Usage</h2>
 *
 * <pre>{@code
 * java -jar methodatlas.jar /path/to/project
 * }</pre>
 *
 * <pre>{@code
 * java -jar methodatlas.jar -plain /path/to/project
 * }</pre>
 *
 * <pre>{@code
 * java -jar methodatlas.jar -ai -ai-provider ollama -ai-model qwen2.5-coder:7b /path/to/project
 * }</pre>
 *
 * @see com.github.javaparser.StaticJavaParser
 * @see org.egothor.methodatlas.ai.AiSuggestionEngine
 * @see #main(String[])
 */
public class MethodAtlasApp {

    private static final Logger LOG = Logger.getLogger(MethodAtlasApp.class.getName());

    /**
     * Output formats supported by the application.
     *
     * <p>
     * The selected mode determines both the emitted header and the per-method
     * output representation.
     * </p>
     */
    private enum OutputMode {
        /**
         * Emits output in comma-separated value format.
         *
         * <p>
         * Fields are escaped according to the rules implemented by
         * {@link #csvEscape(String)}.
         * </p>
         */
        CSV,
        /**
         * Emits output in a human-readable plain text format.
         */
        PLAIN
    }

    /**
     * Parsed command-line configuration used to drive a single application run.
     *
     * @param outputMode selected output mode
     * @param aiOptions  AI configuration controlling provider selection, taxonomy,
     *                   limits, and timeouts
     * @param paths      root paths to scan; when empty, the current working
     *                   directory is scanned
     */
    private record CliConfig(OutputMode outputMode, AiOptions aiOptions, List<Path> paths) {
    }

    /**
     * Program entry point.
     *
     * <p>
     * This method performs the complete startup sequence of the application:
     * </p>
     * <ol>
     * <li>configures JavaParser for Java 21 source syntax</li>
     * <li>parses command-line arguments into a structured runtime
     * configuration</li>
     * <li>initializes the AI suggestion engine when AI support is requested</li>
     * <li>emits the CSV header when CSV output mode is selected</li>
     * <li>scans the requested root paths, or the current directory when no path is
     * supplied</li>
     * </ol>
     *
     * <p>
     * Command-line arguments control both output rendering and optional AI
     * enrichment. Non-option arguments are interpreted as root paths to scan.
     * </p>
     *
     * <p>
     * If no scan path is provided, the method scans the current working directory.
     * If AI support is enabled and engine initialization fails, the method aborts
     * by propagating an {@link IllegalStateException}.
     * </p>
     *
     * @param args command-line arguments controlling output mode, AI configuration,
     *             and scan roots
     * @throws IOException              if traversal of a configured file tree fails
     * @throws IllegalArgumentException if an option is unknown, if a required
     *                                  option value is missing, or if an option
     *                                  value cannot be parsed into the required
     *                                  type
     * @throws IllegalStateException    if AI support is enabled but the AI engine
     *                                  cannot be created successfully
     * @see #parseArgs(String[])
     * @see #buildAiEngine(AiOptions)
     * @see #scanRoot(Path, OutputMode, AiOptions, AiSuggestionEngine)
     */
    public static void main(String[] args) throws IOException {
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(LanguageLevel.JAVA_21);
        StaticJavaParser.setConfiguration(parserConfiguration);

        CliConfig cliConfig = parseArgs(args);
        AiSuggestionEngine aiEngine = buildAiEngine(cliConfig.aiOptions());

        if (cliConfig.outputMode() == OutputMode.CSV) {
            if (cliConfig.aiOptions().enabled()) {
                System.out.println("fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason");
            } else {
                System.out.println("fqcn,method,loc,tags");
            }
        }

        if (cliConfig.paths().isEmpty()) {
            scanRoot(Paths.get("."), cliConfig.outputMode(), cliConfig.aiOptions(), aiEngine);
            return;
        }

        for (Path path : cliConfig.paths()) {
            scanRoot(path, cliConfig.outputMode(), cliConfig.aiOptions(), aiEngine);
        }
    }

    /**
     * Recursively scans a directory tree for Java test source files and processes
     * each matching file.
     *
     * <p>
     * The current implementation selects files whose names end with
     * {@code Test.java}.
     * </p>
     *
     * @param root      root directory to scan
     * @param mode      output mode used for emitted records
     * @param aiOptions AI configuration for the current run
     * @param aiEngine  AI engine used to enrich results, or {@code null} when AI
     *                  support is disabled
     * @throws IOException if traversing the file tree fails
     * @see Files#walk(Path, java.nio.file.FileVisitOption...)
     */
    private static void scanRoot(Path root, OutputMode mode, AiOptions aiOptions, AiSuggestionEngine aiEngine)
            throws IOException {
        LOG.log(Level.INFO, "Scanning {0} for JUnit files", root);

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> path.toString().endsWith("Test.java"))
                    .forEach(path -> processFile(path, mode, aiOptions, aiEngine));
        }
    }

    /**
     * Parses a single Java source file, discovers JUnit test methods, optionally
     * resolves AI suggestions for their enclosing classes, and emits output records
     * for each discovered test method.
     *
     * <p>
     * Parsing and processing failures are logged and do not abort the overall scan.
     * </p>
     *
     * @param path      source file to parse
     * @param mode      output mode used for emitted records
     * @param aiOptions AI configuration for the current run
     * @param aiEngine  AI engine used to enrich results, or {@code null} when AI
     *                  support is disabled
     */
    private static void processFile(Path path, OutputMode mode, AiOptions aiOptions, AiSuggestionEngine aiEngine) {
        try {
            CompilationUnit compilationUnit = StaticJavaParser.parse(path);
            String packageName = compilationUnit.getPackageDeclaration()
                    .map(packageDeclaration -> packageDeclaration.getNameAsString()).orElse("");

            compilationUnit.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String className = clazz.getNameAsString();
                String fqcn = packageName.isEmpty() ? className : packageName + "." + className;
                SuggestionLookup suggestionLookup = resolveSuggestionLookup(clazz, fqcn, aiOptions, aiEngine);

                clazz.findAll(MethodDeclaration.class).forEach(method -> {
                    if (!isJUnitTest(method)) {
                        return;
                    }

                    int loc = countLOC(method);
                    List<String> tags = getTagValues(method);
                    AiMethodSuggestion suggestion = suggestionLookup.find(method.getNameAsString()).orElse(null);

                    emit(mode, aiOptions.enabled(), fqcn, method.getNameAsString(), loc, tags, suggestion);
                });
            });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse: {0}", path);
            e.printStackTrace();
        }
    }

    /**
     * Resolves method-level AI suggestions for a parsed class.
     *
     * <p>
     * If AI support is disabled, no engine is available, or the serialized class
     * source exceeds the configured maximum size, the method returns an empty
     * lookup. Failures produced by the AI engine are logged and also result in an
     * empty lookup.
     * </p>
     *
     * @param clazz     parsed class declaration to analyze
     * @param fqcn      fully qualified class name of {@code clazz}
     * @param aiOptions AI configuration for the current run
     * @param aiEngine  AI engine used to produce suggestions
     * @return lookup of AI suggestions keyed by method name; never {@code null}
     */
    private static SuggestionLookup resolveSuggestionLookup(ClassOrInterfaceDeclaration clazz, String fqcn,
            AiOptions aiOptions, AiSuggestionEngine aiEngine) {
        if (!aiOptions.enabled() || aiEngine == null) {
            return SuggestionLookup.from(null);
        }

        String classSource = clazz.toString();
        if (classSource.length() > aiOptions.maxClassChars()) {
            LOG.log(Level.INFO, "Skipping AI for {0}: class source too large ({1} chars)",
                    new Object[] { fqcn, classSource.length() });
            return SuggestionLookup.from(null);
        }

        try {
            AiClassSuggestion aiClassSuggestion = aiEngine.suggestForClass(fqcn, classSource);
            return SuggestionLookup.from(aiClassSuggestion);
        } catch (AiSuggestionException e) {
            LOG.log(Level.WARNING, "AI suggestion failed for class " + fqcn + ": " + e.getMessage());
            return SuggestionLookup.from(null);
        }
    }

    /**
     * Creates the AI suggestion engine for the current run.
     *
     * <p>
     * If AI support is disabled, the method returns {@code null}. Initialization
     * failures are wrapped in an {@link IllegalStateException} because they prevent
     * execution of the requested AI-enabled mode.
     * </p>
     *
     * @param aiOptions AI configuration for the current run
     * @return initialized AI suggestion engine, or {@code null} when AI is disabled
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
     * Dispatches emission of a single method record to the configured output
     * renderer.
     *
     * @param mode       selected output mode
     * @param aiEnabled  whether AI enrichment is enabled for the current run
     * @param fqcn       fully qualified class name containing the method
     * @param method     test method name
     * @param loc        inclusive line count of the method declaration
     * @param tags       source-level JUnit tags extracted from the method
     * @param suggestion AI suggestion associated with the method, or {@code null}
     *                   if none is available
     */
    private static void emit(OutputMode mode, boolean aiEnabled, String fqcn, String method, int loc, List<String> tags,
            AiMethodSuggestion suggestion) {
        if (mode == OutputMode.PLAIN) {
            emitPlain(aiEnabled, fqcn, method, loc, tags, suggestion);
            return;
        }

        emitCsv(aiEnabled, fqcn, method, loc, tags, suggestion);
    }

    /**
     * Emits a single method record in plain text format.
     *
     * <p>
     * When AI support is disabled, only source-derived metadata is emitted. When AI
     * support is enabled, the method appends AI-derived fields to the same line.
     * Missing AI values are rendered as {@code -}.
     * </p>
     *
     * @param aiEnabled  whether AI enrichment is enabled for the current run
     * @param fqcn       fully qualified class name containing the method
     * @param method     test method name
     * @param loc        inclusive line count of the method declaration
     * @param tags       source-level JUnit tags extracted from the method
     * @param suggestion AI suggestion associated with the method, or {@code null}
     *                   if none is available
     */
    private static void emitPlain(boolean aiEnabled, String fqcn, String method, int loc, List<String> tags,
            AiMethodSuggestion suggestion) {
        String existingTags = tags.isEmpty() ? "-" : String.join(";", tags);

        if (!aiEnabled) {
            System.out.println(fqcn + ", " + method + ", LOC=" + loc + ", TAGS=" + existingTags);
            return;
        }

        String aiSecurity = suggestion == null ? "-" : Boolean.toString(suggestion.securityRelevant());
        String aiDisplayName = suggestion == null || suggestion.displayName() == null ? "-" : suggestion.displayName();
        String aiTags = suggestion == null || suggestion.tags() == null || suggestion.tags().isEmpty() ? "-"
                : String.join(";", suggestion.tags());
        String aiReason = suggestion == null || suggestion.reason() == null || suggestion.reason().isBlank() ? "-"
                : suggestion.reason();

        System.out.println(fqcn + ", " + method + ", LOC=" + loc + ", TAGS=" + existingTags + ", AI_SECURITY="
                + aiSecurity + ", AI_DISPLAY=" + aiDisplayName + ", AI_TAGS=" + aiTags + ", AI_REASON=" + aiReason);
    }

    /**
     * Emits a single method record in CSV format.
     *
     * <p>
     * All textual fields are escaped using {@link #csvEscape(String)}. When AI
     * support is disabled, only the base columns are emitted. When AI support is
     * enabled, the AI-related columns are appended in the header order established
     * by {@link #main(String[])}.
     * </p>
     *
     * @param aiEnabled  whether AI enrichment is enabled for the current run
     * @param fqcn       fully qualified class name containing the method
     * @param method     test method name
     * @param loc        inclusive line count of the method declaration
     * @param tags       source-level JUnit tags extracted from the method
     * @param suggestion AI suggestion associated with the method, or {@code null}
     *                   if none is available
     */
    private static void emitCsv(boolean aiEnabled, String fqcn, String method, int loc, List<String> tags,
            AiMethodSuggestion suggestion) {
        String existingTags = tags.isEmpty() ? "" : String.join(";", tags);

        if (!aiEnabled) {
            System.out.println(csvEscape(fqcn) + "," + csvEscape(method) + "," + loc + "," + csvEscape(existingTags));
            return;
        }

        String aiSecurity = suggestion == null ? "" : Boolean.toString(suggestion.securityRelevant());
        String aiDisplayName = suggestion == null || suggestion.displayName() == null ? "" : suggestion.displayName();
        String aiTags = suggestion == null || suggestion.tags() == null ? "" : String.join(";", suggestion.tags());
        String aiReason = suggestion == null || suggestion.reason() == null ? "" : suggestion.reason();

        System.out.println(csvEscape(fqcn) + "," + csvEscape(method) + "," + loc + "," + csvEscape(existingTags) + ","
                + csvEscape(aiSecurity) + "," + csvEscape(aiDisplayName) + "," + csvEscape(aiTags) + ","
                + csvEscape(aiReason));
    }

    /**
     * Escapes a value for CSV output.
     *
     * <p>
     * If the value contains a comma, double quote, carriage return, or line feed,
     * it is wrapped in double quotes and embedded quotes are doubled. A
     * {@code null} input is converted to an empty field.
     * </p>
     *
     * @param value value to escape; may be {@code null}
     * @return CSV-safe representation of {@code value}
     */
    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }

        boolean mustQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;

        if (!mustQuote) {
            return value;
        }

        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * Parses command-line arguments into a structured configuration object.
     *
     * <p>
     * The method recognizes output mode switches, AI-related options, and one or
     * more scan paths. Unrecognized options beginning with {@code -} cause an
     * {@link IllegalArgumentException}.
     * </p>
     *
     * @param args raw command-line arguments
     * @return parsed command-line configuration
     * @throws IllegalArgumentException if an option value is missing, malformed, or
     *                                  unsupported
     */
    private static CliConfig parseArgs(String[] args) {
        OutputMode outputMode = OutputMode.CSV;
        List<Path> paths = new ArrayList<>();
        AiOptions.Builder aiBuilder = AiOptions.builder();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "-plain" -> outputMode = OutputMode.PLAIN;
                case "-ai" -> aiBuilder.enabled(true);
                case "-ai-provider" -> aiBuilder.provider(AiProvider.valueOf(nextArg(args, ++i, arg).toUpperCase()));
                case "-ai-model" -> aiBuilder.modelName(nextArg(args, ++i, arg));
                case "-ai-base-url" -> aiBuilder.baseUrl(nextArg(args, ++i, arg));
                case "-ai-api-key" -> aiBuilder.apiKey(nextArg(args, ++i, arg));
                case "-ai-api-key-env" -> aiBuilder.apiKeyEnv(nextArg(args, ++i, arg));
                case "-ai-taxonomy" -> aiBuilder.taxonomyFile(Paths.get(nextArg(args, ++i, arg)));
                case "-ai-taxonomy-mode" ->
                    aiBuilder.taxonomyMode(AiOptions.TaxonomyMode.valueOf(nextArg(args, ++i, arg).toUpperCase()));
                case "-ai-max-class-chars" -> aiBuilder.maxClassChars(Integer.parseInt(nextArg(args, ++i, arg)));
                case "-ai-timeout-sec" ->
                    aiBuilder.timeout(Duration.ofSeconds(Long.parseLong(nextArg(args, ++i, arg))));
                case "-ai-max-retries" -> aiBuilder.maxRetries(Integer.parseInt(nextArg(args, ++i, arg)));
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                    }
                    paths.add(Paths.get(arg));
                }
            }
        }

        return new CliConfig(outputMode, aiBuilder.build(), paths);
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

    /**
     * Determines whether a method declaration represents a supported JUnit Jupiter
     * test method.
     *
     * <p>
     * The current implementation recognizes methods annotated with {@code @Test},
     * {@code @ParameterizedTest}, or {@code @RepeatedTest} by simple annotation
     * name.
     * </p>
     *
     * @param method method declaration to inspect
     * @return {@code true} if the method is treated as a test method; {@code false}
     *         otherwise
     */
    private static boolean isJUnitTest(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            if ("Test".equals(name) || "ParameterizedTest".equals(name) || "RepeatedTest".equals(name)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Computes the inclusive line count of a method declaration from its source
     * range.
     *
     * <p>
     * If the parser did not retain source position information for the method, the
     * method returns {@code 0}.
     * </p>
     *
     * @param method method declaration whose size should be measured
     * @return inclusive line count, or {@code 0} if no range information is
     *         available
     */
    private static int countLOC(MethodDeclaration method) {
        return method.getRange().map(range -> range.end.line - range.begin.line + 1).orElse(0);
    }

    /**
     * Extracts all JUnit tag values declared on a method.
     *
     * <p>
     * The method supports both direct {@code @Tag} annotations and the
     * container-style {@code @Tags} annotation. Tags are returned in declaration
     * order.
     * </p>
     *
     * @param method method declaration whose annotations should be inspected
     * @return list of extracted tag values; possibly empty but never {@code null}
     */
    private static List<String> getTagValues(MethodDeclaration method) {
        List<String> tagValues = new ArrayList<>();

        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();

            if ("Tag".equals(name)) {
                extractTagValue(annotation).ifPresent(tagValues::add);
            } else if ("Tags".equals(name)) {
                extractTagsContainerValues(annotation, tagValues);
            }
        }

        return tagValues;
    }

    /**
     * Extracts tag values from a JUnit {@code @Tags} container annotation.
     *
     * <p>
     * Both the single-member form {@code @Tags({@Tag("a"), @Tag("b")})} and the
     * normal form {@code @Tags(value = {...})} are supported.
     * </p>
     *
     * @param annotation annotation expected to represent {@code @Tags}
     * @param tagValues  destination list to which extracted tag values are appended
     */
    private static void extractTagsContainerValues(AnnotationExpr annotation, List<String> tagValues) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            Expression memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();
            extractTagsFromContainerValue(memberValue, tagValues);
            return;
        }

        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if ("value".equals(pair.getNameAsString())) {
                    extractTagsFromContainerValue(pair.getValue(), tagValues);
                }
            }
        }
    }

    /**
     * Extracts individual {@code @Tag} values from the value expression of a
     * {@code @Tags} container annotation.
     *
     * <p>
     * If the supplied expression is not an array initializer, the method does
     * nothing.
     * </p>
     *
     * @param value     expression holding the container contents
     * @param tagValues destination list to which extracted tag values are appended
     */
    private static void extractTagsFromContainerValue(Expression value, List<String> tagValues) {
        if (!value.isArrayInitializerExpr()) {
            return;
        }

        ArrayInitializerExpr array = value.asArrayInitializerExpr();
        for (Expression expression : array.getValues()) {
            if (expression.isAnnotationExpr()) {
                extractTagValue(expression.asAnnotationExpr()).ifPresent(tagValues::add);
            }
        }
    }

    /**
     * Extracts the value from a single JUnit {@code @Tag} annotation.
     *
     * <p>
     * Both the single-member form {@code @Tag("x")} and the normal form
     * {@code @Tag(value = "x")} are supported.
     * </p>
     *
     * @param annotation annotation expected to represent {@code @Tag}
     * @return extracted tag value, or {@link Optional#empty()} if the annotation is
     *         not a supported {@code @Tag} form
     */
    private static Optional<String> extractTagValue(AnnotationExpr annotation) {
        if (!"Tag".equals(annotation.getNameAsString())) {
            return Optional.empty();
        }

        if (annotation.isSingleMemberAnnotationExpr()) {
            Expression memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();
            if (memberValue.isStringLiteralExpr()) {
                return Optional.of(memberValue.asStringLiteralExpr().asString());
            }
            return Optional.empty();
        }

        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if ("value".equals(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr()) {
                    return Optional.of(pair.getValue().asStringLiteralExpr().asString());
                }
            }
        }

        return Optional.empty();
    }
}
