package org.egothor.methodatlas;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.egothor.methodatlas.ai.ManualConsumeEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.command.ApplyTagsCommand;
import org.egothor.methodatlas.command.ApplyTagsFromCsvCommand;
import org.egothor.methodatlas.command.CommandSupport;
import org.egothor.methodatlas.command.DiffCommand;
import org.egothor.methodatlas.command.GitHubAnnotationsCommand;
import org.egothor.methodatlas.command.ManualPrepareCommand;
import org.egothor.methodatlas.command.SarifCommand;
import org.egothor.methodatlas.command.ScanCommand;

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
 * @see org.egothor.methodatlas.command.Command
 * @see #main(String[])
 */
public final class MethodAtlasApp {

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
     * Executes a full application run by routing to the appropriate
     * {@link org.egothor.methodatlas.command.Command} implementation.
     *
     * <p>
     * This method is the primary entry point for programmatic and test use. It
     * parses arguments, identifies the requested operating mode, constructs the
     * matching command object, and delegates execution to it.
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
                return new DiffCommand(Path.of(args[i + 1]), Path.of(args[i + 2])).execute(out);
            }
        }

        CliConfig cliConfig = CliArgs.parse(args);
        ClassificationOverride override = CommandSupport.loadClassificationOverride(cliConfig.overrideFile());
        AiResultCache aiCache = CommandSupport.buildAiCache(cliConfig.aiCacheFile());

        TestDiscoveryConfig discoveryConfig =
                new TestDiscoveryConfig(cliConfig.fileSuffixes(), cliConfig.testMarkers(), cliConfig.properties());

        // Manual prepare phase: write AI prompt work files; no CSV output.
        if (cliConfig.manualMode() instanceof ManualMode.Prepare prepare) {
            return new ManualPrepareCommand(prepare, cliConfig, discoveryConfig).execute(out);
        }

        // Determine AI engine: manual consume reads from files; normal mode calls APIs.
        AiSuggestionEngine aiEngine;
        if (cliConfig.manualMode() instanceof ManualMode.Consume consume) {
            aiEngine = new ManualConsumeEngine(consume.responseDir());
        } else {
            aiEngine = CommandSupport.buildAiEngine(cliConfig.aiOptions());
        }

        // Apply-tags-from-csv mode: apply reviewed CSV decisions to source files.
        if (cliConfig.applyTagsFromCsvFile() != null) {
            return new ApplyTagsFromCsvCommand(cliConfig, discoveryConfig).execute(out);
        }

        // Apply-tags mode: annotate source files; no report emitted.
        if (cliConfig.applyTags()) {
            return new ApplyTagsCommand(cliConfig, discoveryConfig, aiEngine, override, aiCache).execute(out);
        }

        // SARIF mode: buffer all records; write JSON once after the scan completes.
        if (cliConfig.outputMode() == OutputMode.SARIF) {
            return new SarifCommand(cliConfig, discoveryConfig, aiEngine, override, aiCache).execute(out);
        }

        // GitHub Annotations mode: emit ::notice/::warning workflow commands.
        if (cliConfig.outputMode() == OutputMode.GITHUB_ANNOTATIONS) {
            return new GitHubAnnotationsCommand(cliConfig, discoveryConfig, aiEngine, override, aiCache).execute(out);
        }

        // CSV / PLAIN mode: emit incrementally (default).
        return new ScanCommand(cliConfig, discoveryConfig, aiEngine, override, aiCache).execute(out);
    }
}
