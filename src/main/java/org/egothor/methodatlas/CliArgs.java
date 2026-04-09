package org.egothor.methodatlas;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiProvider;

/**
 * Parses command-line arguments into a {@link CliConfig}.
 *
 * <p>
 * This class centralises all argument-parsing logic for the MethodAtlas
 * application. It is intentionally separated from {@link MethodAtlasApp} to
 * keep each class focused and below the project's cyclomatic-complexity
 * threshold.
 * </p>
 *
 * <p>
 * This class is a non-instantiable utility holder.
 * </p>
 *
 * @see CliConfig
 * @see MethodAtlasApp
 */
final class CliArgs {

    private static final String DEFAULT_FILE_SUFFIX = "Test.java";

    /**
     * Prevents instantiation of this utility class.
     */
    private CliArgs() {
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
    /* default */ static CliConfig parse(String... args) {
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
     * @throws IllegalArgumentException if a required value token is missing or
     *                                  the flag is unrecognised
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
                builder.taxonomyMode(
                        AiOptions.TaxonomyMode.valueOf(nextArg(args, ++idx, arg).toUpperCase(Locale.ROOT)));
            case "-ai-max-class-chars" -> builder.maxClassChars(Integer.parseInt(nextArg(args, ++idx, arg)));
            case "-ai-timeout-sec" ->
                builder.timeout(Duration.ofSeconds(Long.parseLong(nextArg(args, ++idx, arg))));
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
