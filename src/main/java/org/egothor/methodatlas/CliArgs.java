package org.egothor.methodatlas;

import java.io.IOException;
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
 * When a {@code -config <file>} argument is present it is processed first
 * (via a pre-scan) so that the YAML file provides default values before
 * individual command-line flags are evaluated. Command-line flags always take
 * precedence over values from the YAML configuration file.
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
    private static final String FLAG_CONFIG = "-config";
    private static final String FLAG_AI_CACHE = "-ai-cache";

    /**
     * Prevents instantiation of this utility class.
     */
    private CliArgs() {
    }

    /**
     * Parses command-line arguments into a structured configuration object.
     *
     * <p>
     * If a {@code -config <file>} argument is present it is loaded first and
     * its values seed the initial configuration. Subsequent command-line flags
     * override those defaults.
     * </p>
     *
     * @param args raw command-line arguments
     * @return parsed command-line configuration
     * @throws IllegalArgumentException if an option value is missing, malformed,
     *                                  or unsupported, or if the config file
     *                                  cannot be read
     */
    @SuppressWarnings({"PMD.AvoidReassigningLoopVariables", "PMD.CyclomaticComplexity", "PMD.NPathComplexity"})
    /* default */ static CliConfig parse(String... args) {
        // Pre-scan for -config to load YAML defaults before processing other flags.
        YamlConfig.YamlConfigFile yamlConfig = loadYamlConfigFromArgs(args);

        // Seed initial values from YAML (command-line flags will override these).
        OutputMode outputMode = resolveOutputModeFromYaml(yamlConfig);
        boolean emitMetadata = yamlConfig != null && yamlConfig.emitMetadata;
        List<String> fileSuffixes = yamlConfig != null && yamlConfig.fileSuffixes != null
                ? new ArrayList<>(yamlConfig.fileSuffixes) : new ArrayList<>();
        Set<String> testAnnotations = yamlConfig != null && yamlConfig.testAnnotations != null
                ? new LinkedHashSet<>(yamlConfig.testAnnotations) : new LinkedHashSet<>();
        AiOptions.Builder aiBuilder = AiOptions.builder();
        if (yamlConfig != null && yamlConfig.ai != null) {
            applyYamlAiConfig(aiBuilder, yamlConfig.ai);
        }

        List<Path> paths = new ArrayList<>();
        String manualWorkDir = null;
        String manualResponseDir = null;
        boolean manualIsConsume = false;
        boolean applyTags = false;
        boolean contentHash = yamlConfig != null && yamlConfig.contentHash;
        Path overrideFilePath = yamlConfig != null && yamlConfig.overrideFile != null
                ? Paths.get(yamlConfig.overrideFile) : null;
        boolean securityOnly = yamlConfig != null && yamlConfig.securityOnly;
        Path aiCacheFile = null;
        // Tracks whether the first CLI -file-suffix has been seen; when it is,
        // subsequent -file-suffix values are appended rather than replacing defaults.
        boolean cliFileSuffixSet = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (FLAG_AI_CACHE.equals(arg)) {
                aiCacheFile = Paths.get(nextArg(args, ++i, arg));
                continue;
            }
            if (arg.startsWith("-ai")) {
                i = applyAiArg(arg, args, i, aiBuilder);
                continue;
            }
            switch (arg) {
                case "-plain" -> outputMode = OutputMode.PLAIN;
                case "-sarif" -> outputMode = OutputMode.SARIF;
                case "-github-annotations" -> outputMode = OutputMode.GITHUB_ANNOTATIONS;
                case "-apply-tags" -> applyTags = true;
                case "-content-hash" -> contentHash = true;
                case FLAG_CONFIG -> i++; // value already consumed in pre-scan; skip here
                case "-file-suffix" -> {
                    if (!cliFileSuffixSet) {
                        // First CLI -file-suffix replaces YAML defaults
                        fileSuffixes.clear();
                        cliFileSuffixSet = true;
                    }
                    fileSuffixes.add(nextArg(args, ++i, arg));
                }
                case "-test-annotation" -> testAnnotations.add(nextArg(args, ++i, arg));
                case "-emit-metadata" -> emitMetadata = true;
                case "-security-only" -> securityOnly = true;
                case "-override-file" -> overrideFilePath = Paths.get(nextArg(args, ++i, arg));
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
                emitMetadata, manualMode, applyTags, contentHash, overrideFilePath, securityOnly, aiCacheFile);
    }

    // -------------------------------------------------------------------------
    // YAML config helpers
    // -------------------------------------------------------------------------

    /**
     * Pre-scans {@code args} for a {@code -config <file>} argument and loads the
     * YAML file if found.
     *
     * @param args raw command-line arguments
     * @return parsed YAML config, or {@code null} when no {@code -config} flag is
     *         present
     * @throws IllegalArgumentException if the config file cannot be read
     */
    private static YamlConfig.YamlConfigFile loadYamlConfigFromArgs(String... args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (FLAG_CONFIG.equals(args[i])) {
                Path configPath = Paths.get(args[i + 1]);
                try {
                    return YamlConfig.load(configPath);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Cannot load config file: " + configPath, e);
                }
            }
        }
        return null;
    }

    /**
     * Derives the initial {@link OutputMode} from a loaded YAML config.
     *
     * @param yamlConfig YAML config, or {@code null}
     * @return resolved output mode; defaults to {@link OutputMode#CSV}
     */
    private static OutputMode resolveOutputModeFromYaml(YamlConfig.YamlConfigFile yamlConfig) {
        if (yamlConfig == null || yamlConfig.outputMode == null) {
            return OutputMode.CSV;
        }
        return switch (yamlConfig.outputMode.toLowerCase(Locale.ROOT)) {
            case "plain" -> OutputMode.PLAIN;
            case "sarif" -> OutputMode.SARIF;
            default -> OutputMode.CSV;
        };
    }

    /**
     * Seeds the AI options builder from YAML configuration values.
     *
     * @param builder   AI options builder to update
     * @param aiConfig  AI section of the YAML config; never {@code null}
     */
    @SuppressWarnings("PMD.NPathComplexity")
    private static void applyYamlAiConfig(AiOptions.Builder builder, YamlConfig.YamlAiConfig aiConfig) {
        if (Boolean.TRUE.equals(aiConfig.enabled)) {
            builder.enabled(true);
        }
        if (aiConfig.provider != null) {
            builder.provider(AiProvider.valueOf(aiConfig.provider.toUpperCase(Locale.ROOT)));
        }
        if (aiConfig.model != null) {
            builder.modelName(aiConfig.model);
        }
        if (aiConfig.baseUrl != null) {
            builder.baseUrl(aiConfig.baseUrl);
        }
        if (aiConfig.apiKey != null) {
            builder.apiKey(aiConfig.apiKey);
        }
        if (aiConfig.apiKeyEnv != null) {
            builder.apiKeyEnv(aiConfig.apiKeyEnv);
        }
        if (aiConfig.taxonomyFile != null) {
            builder.taxonomyFile(Paths.get(aiConfig.taxonomyFile));
        }
        if (aiConfig.taxonomyMode != null) {
            builder.taxonomyMode(
                    AiOptions.TaxonomyMode.valueOf(aiConfig.taxonomyMode.toUpperCase(Locale.ROOT)));
        }
        if (aiConfig.maxClassChars != null) {
            builder.maxClassChars(aiConfig.maxClassChars);
        }
        if (aiConfig.timeoutSec != null) {
            builder.timeout(Duration.ofSeconds(aiConfig.timeoutSec));
        }
        if (aiConfig.maxRetries != null) {
            builder.maxRetries(aiConfig.maxRetries);
        }
        if (Boolean.TRUE.equals(aiConfig.confidence)) {
            builder.confidence(true);
        }
        if (aiConfig.apiVersion != null) {
            builder.apiVersion(aiConfig.apiVersion);
        }
    }

    // -------------------------------------------------------------------------
    // AI argument helper
    // -------------------------------------------------------------------------

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
            case "-ai-confidence" -> builder.confidence(true);
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
            case "-ai-api-version" -> builder.apiVersion(nextArg(args, ++idx, arg));
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
