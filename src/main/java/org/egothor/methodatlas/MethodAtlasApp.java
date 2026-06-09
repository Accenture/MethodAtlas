package org.egothor.methodatlas;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.egothor.methodatlas.ai.ManualConsumeEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.command.AiRuntimeBuilder;
import org.egothor.methodatlas.command.ApplyTagsCommand;

import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.emit.OutputMode;
import org.egothor.methodatlas.command.ApplyTagsFromCsvCommand;
import org.egothor.methodatlas.command.CheckPromptsCommand;
import org.egothor.methodatlas.command.DiffCommand;
import org.egothor.methodatlas.command.GitHubAnnotationsCommand;
import org.egothor.methodatlas.command.JsonCommand;
import org.egothor.methodatlas.command.ManualPrepareCommand;
import org.egothor.methodatlas.command.OverrideLoader;
import org.egothor.methodatlas.command.PluginLoader;
import org.egothor.methodatlas.command.SarifCommand;
import org.egothor.methodatlas.command.ScanCommand;
import org.egothor.methodatlas.command.ScanOrchestrator;
import org.egothor.methodatlas.coverage.CoverageFacade;
import org.egothor.methodatlas.receipt.ReceiptFacade;
import org.egothor.methodatlas.evidence.EvidenceFramework;
import org.egothor.methodatlas.evidence.EvidencePackCommand;
import org.egothor.methodatlas.evidence.EvidencePackOptions;
import org.egothor.methodatlas.evidence.GenSigningKeyCommand;

/**
 * Command-line entry point that parses the arguments and routes the invocation
 * to the matching {@link org.egothor.methodatlas.command.Command} or utility
 * mode.
 *
 * <p>
 * This class owns no discovery or parsing logic of its own. It parses arguments
 * into a {@link CliConfig} (via {@link CliArgs}), selects the operating mode,
 * builds the collaborators that mode needs, and delegates execution. Test
 * discovery is performed by the language plugins on the classpath (loaded
 * through {@link java.util.ServiceLoader}); AI enrichment, output emission, and
 * the evidence-pack, coverage, and receipt features are each handled by their
 * own command or facade.
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
 * <li>{@code -json} — emits a flat JSON array; each element carries the same
 * fields as CSV with {@code tags} and {@code ai_tags} as arrays and numeric
 * values as JSON numbers</li>
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
 * <li>{@code -min-confidence <threshold>} — silently drops methods whose AI
 * confidence score is below {@code threshold} (range {@code 0.0–1.0}); only
 * effective when {@code -ai-confidence} is also enabled</li>
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
 * <li>{@code -promote-ai} — <strong>risky, not recommended</strong> opt-in for
 * {@code -apply-tags-from-csv}: fills blank tags/display_name from the AI columns
 * (unvalidated AI output reaches source); off by default</li>
 * <li>{@code -help} / {@code --help} / {@code -h} — print usage and exit</li>
 * <li>{@code -emit-source-root} — adds a {@code source_root} column (CSV) and a
 * {@code SRCROOT=} token (plain text) identifying which scan root each record
 * came from; essential in multi-root projects where the same fully qualified
 * class name can appear under different source trees; no effect on SARIF or
 * GitHub Annotations output</li>
 * </ul>
 *
 * <p>
 * Any remaining non-option arguments are interpreted as root paths to scan. If
 * no scan path is supplied, the current working directory is scanned.
 * </p>
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

    /** Logger for receipt-emission warnings; receipt failures never abort the scan. */
    private static final Logger LOG = Logger.getLogger(MethodAtlasApp.class.getName());

    /** Tool version fallback when the JAR manifest carries no Implementation-Version. */
    private static final String DEV_VERSION = "dev";

    /** Exit code returned when CLI validation rejects the requested invocation. */
    private static final int EXIT_BAD_ARGS = 2;
    /** Exit code used when an evidence-pack framework token cannot be parsed. */
    private static final int EXIT_BAD_FRAMEWORK = 2;

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
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops") // DiffCommand is created inside the loop but returned immediately
    /* default */ static int run(String[] args, PrintWriter out) throws IOException {
        // -help is handled before argument parsing so it works even with no
        // other (or otherwise invalid) arguments and never trips the
        // "Unknown argument" path.
        for (String arg : args) {
            if ("-help".equals(arg) || "--help".equals(arg) || "-h".equals(arg)) {
                Usage.print(out);
                return 0;
            }
        }

        // -diff and -gen-signing-key are utility modes handled before full
        // argument parsing; each owns its own small set of flags and never
        // participates in the scan pipeline, so all other flags are ignored.
        for (int i = 0; i < args.length; i++) {
            if (FLAG_DIFF.equals(args[i])) {
                if (i + 2 >= args.length) {
                    throw new IllegalArgumentException(
                            "-diff requires two arguments: -diff <before.csv> <after.csv>");
                }
                return new DiffCommand(Path.of(args[i + 1]), Path.of(args[i + 2])).execute(out);
            }
            if (GenSigningKeyCommand.FLAG_GEN_SIGNING_KEY.equals(args[i])) {
                return GenSigningKeyCommand.run(args, out);
            }
            if (CheckPromptsCommand.FLAG_CHECK_PROMPTS.equals(args[i])) {
                return CheckPromptsCommand.fromArgs(args).execute(out);
            }
        }

        CliConfig cliConfig = CliArgs.parse(args);
        if (cliConfig == null) {
            // CliArgs already printed an actionable stderr message; signal
            // bad-args via the conventional exit code 2.
            return EXIT_BAD_ARGS;
        }

        // Load the coverage mapping up-front so a malformed file aborts the run
        // before the scan starts, avoiding wasted work and ambiguous errors.
        CoverageFacade.Handle coverageHandle;
        try {
            coverageHandle = prepareCoverageHandle(cliConfig);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Error loading coverage mapping: " + e.getMessage());
            return EXIT_BAD_ARGS;
        }

        // Establish the run identity once and place it in the thread-local
        // context so the JUL formatter (Item 20) can prepend the correlation
        // id to every log record emitted during this invocation. clear() in
        // the finally block keeps the thread-local from outliving the run
        // when MethodAtlas is invoked programmatically (the standard CLI
        // exits the JVM anyway).
        String version = MethodAtlasApp.class.getPackage().getImplementationVersion();
        ScanRun scanRun = ScanRun.create(version, cliConfig.toString());
        ScanRunContext.set(scanRun);
        try {
            int exit = runWithScanRun(out, cliConfig, coverageHandle);
            if (cliConfig.emitReceipt()) {
                emitReceipt(cliConfig, version);
            }
            if (coverageHandle != null) {
                writeCoverage(cliConfig, version, coverageHandle);
            }
            return exit;
        } finally {
            ScanRunContext.clear();
        }
    }

    /**
     * Loads the coverage mapping when {@code -emit-coverage} is active.
     *
     * @param cliConfig parsed CLI configuration
     * @return prepared handle or {@code null} when coverage mode is not active
     * @throws IOException              if the mapping file cannot be read
     * @throws IllegalArgumentException if the mapping file fails validation
     */
    private static CoverageFacade.Handle prepareCoverageHandle(CliConfig cliConfig)
            throws IOException {
        if (!cliConfig.emitCoverage()) {
            return null;
        }
        return CoverageFacade.prepare(cliConfig.coverageMappingFile(), cliConfig.minConfidence());
    }

    /**
     * Writes the coverage report. Errors are logged and swallowed so a
     * coverage-write failure never demotes a successful scan.
     *
     * @param cliConfig parsed CLI configuration
     * @param version   resolved tool version
     * @param handle    prepared coverage handle; never {@code null}
     */
    private static void writeCoverage(CliConfig cliConfig, String version,
            CoverageFacade.Handle handle) {
        String toolVersion = version != null ? version : DEV_VERSION;
        Path target = cliConfig.coverageFile() != null
                ? cliConfig.coverageFile()
                : Path.of(CoverageFacade.DEFAULT_COVERAGE_FILENAME);
        try {
            handle.write(toolVersion, target);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING,
                        "Could not write coverage report: {0}", e.getMessage());
            }
        }
    }

    /**
     * Writes a reproducibility receipt for the just-completed scan.
     *
     * <p>
     * Failures are logged at WARNING level and swallowed so a receipt-write
     * error never turns a successful scan into a non-zero exit code.
     * </p>
     *
     * @param cliConfig parsed CLI configuration whose
     *                  {@link CliConfig#emitReceipt()} is already known to be
     *                  {@code true}
     * @param version   tool version resolved from the JAR manifest; {@code null}
     *                  resolves to {@code "dev"}
     */
    private static void emitReceipt(CliConfig cliConfig, String version) {
        String toolVersion = version != null ? version : DEV_VERSION;
        String modeName = cliConfig.outputMode().name();
        try {
            ReceiptFacade.emit(cliConfig, toolVersion, modeName);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING,
                        "Could not write reproducibility receipt: {0}", e.getMessage());
            }
        }
    }

    private static int runWithScanRun(PrintWriter out, CliConfig cliConfig,
            CoverageFacade.Handle coverageHandle) throws IOException {
        AiRuntimeBuilder aiRuntimeBuilder = new AiRuntimeBuilder();
        ClassificationOverride override = new OverrideLoader().load(cliConfig.overrideFile());
        AiResultCache aiCache = aiRuntimeBuilder.buildCache(cliConfig.aiCacheFile());

        TestDiscoveryConfig discoveryConfig =
                new TestDiscoveryConfig(cliConfig.fileSuffixes(), cliConfig.testMarkers(), cliConfig.properties());

        // One PluginLoader + one ScanOrchestrator are shared by every command in
        // this run; both are stateless and the providers they resolve are owned
        // (and closed) by the command that requested them. When -emit-coverage
        // is active the orchestrator carries the coverage sink as an extra
        // fan-out — every command mode sees the same fan-out automatically.
        PluginLoader pluginLoader = new PluginLoader();
        java.util.Optional<org.egothor.methodatlas.emit.TestMethodSink> extraSink =
                coverageHandle == null
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(coverageHandle.asSink());
        ScanOrchestrator scanOrchestrator = new ScanOrchestrator(pluginLoader, extraSink);

        // Manual prepare phase: write AI prompt work files; no CSV output.
        if (cliConfig.manualMode() instanceof ManualMode.Prepare prepare) {
            return new ManualPrepareCommand(prepare, cliConfig, discoveryConfig, pluginLoader).execute(out);
        }

        // Determine AI engine: manual consume reads from files; normal mode calls APIs.
        AiSuggestionEngine aiEngine;
        if (cliConfig.manualMode() instanceof ManualMode.Consume consume) {
            aiEngine = new ManualConsumeEngine(consume.responseDir());
        } else {
            aiEngine = aiRuntimeBuilder.buildEngine(cliConfig.aiOptions());
        }

        // Apply-tags-from-csv mode: apply reviewed CSV decisions to source files.
        if (cliConfig.applyTagsFromCsvFile() != null) {
            return new ApplyTagsFromCsvCommand(cliConfig, discoveryConfig, pluginLoader).execute(out);
        }

        // Apply-tags mode: annotate source files; no report emitted.
        if (cliConfig.applyTags()) {
            return new ApplyTagsCommand(cliConfig, discoveryConfig, aiEngine, override, aiCache,
                    pluginLoader, scanOrchestrator).execute(out);
        }

        // Evidence-pack mode: produces a tamper-evident self-contained directory
        // (SARIF + CSV + manifest + optional ZeroEcho signature). Must precede the
        // SARIF/JSON/GitHub-annotation branches because those formats are
        // re-used internally; the dispatch decision is owned by this command.
        if (cliConfig.evidencePackFramework() != null) {
            return dispatchEvidencePack(cliConfig, discoveryConfig, aiEngine, override, aiCache,
                    scanOrchestrator);
        }

        // SARIF mode: buffer all records; write JSON once after the scan completes.
        if (cliConfig.outputMode() == OutputMode.SARIF) {
            return new SarifCommand(cliConfig, discoveryConfig, aiEngine, override, aiCache,
                    scanOrchestrator).execute(out);
        }

        // JSON mode: buffer all records; write flat JSON array after scan completes.
        if (cliConfig.outputMode() == OutputMode.JSON) {
            return new JsonCommand(cliConfig, discoveryConfig, aiEngine, override, aiCache,
                    pluginLoader, scanOrchestrator).execute(out);
        }

        // GitHub Annotations mode: emit ::notice/::warning workflow commands.
        if (cliConfig.outputMode() == OutputMode.GITHUB_ANNOTATIONS) {
            return new GitHubAnnotationsCommand(cliConfig, discoveryConfig, aiEngine, override, aiCache,
                    scanOrchestrator).execute(out);
        }

        // CSV / PLAIN mode: emit incrementally (default).
        return new ScanCommand(cliConfig, discoveryConfig, aiEngine, override, aiCache,
                pluginLoader, scanOrchestrator).execute(out);
    }

    /**
     * Parses the {@code -evidence-pack} framework token, assembles
     * {@link EvidencePackOptions}, runs the {@link EvidencePackCommand}, and
     * prints a one-line summary to {@code stderr}. The signing key is read from
     * a ZeroEcho keyring file (a plaintext {@code KeyringStore}); ZeroEcho
     * keyrings are not password-protected, so no password is collected.
     *
     * @param cliConfig         parsed CLI configuration
     * @param discoveryConfig   discovery configuration forwarded to providers
     * @param aiEngine          AI engine, or {@code null}
     * @param override          classification override
     * @param aiCache           AI result cache
     * @param scanOrchestrator  shared scan orchestrator
     * @return exit code propagated from {@link EvidencePackCommand#execute()}
     *         or {@value #EXIT_BAD_FRAMEWORK} when the framework token is invalid
     * @throws IOException if the underlying scan or I/O fails
     */
    private static int dispatchEvidencePack(CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, ClassificationOverride override, AiResultCache aiCache,
            ScanOrchestrator scanOrchestrator) throws IOException {
        EvidenceFramework framework;
        try {
            framework = EvidenceFramework.parse(cliConfig.evidencePackFramework());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return EXIT_BAD_FRAMEWORK;
        }
        EvidencePackOptions packOptions = new EvidencePackOptions(
                framework,
                cliConfig.evidencePackDir(),
                cliConfig.evidencePackOverwrite(),
                cliConfig.evidencePackKeyringFile(),
                cliConfig.evidencePackKeyringEnv(),
                cliConfig.evidencePackKeyAlias(),
                cliConfig.evidencePackSignAlgo());
        EvidencePackCommand command = new EvidencePackCommand(cliConfig, packOptions, discoveryConfig,
                aiEngine, override, aiCache, scanOrchestrator);
        int exit = command.execute();
        announcePack(command, packOptions);
        return exit;
    }

    /**
     * Prints the one-line outcome banner that documents how the pack was
     * produced. Always written to {@code System.err} so the stdout stream
     * remains usable for piping.
     *
     * @param command     executed command, used to retrieve the absolute path
     * @param packOptions options driving the command
     */
    private static void announcePack(EvidencePackCommand command, EvidencePackOptions packOptions) {
        String absolute = command.outputDir().toString();
        if (packOptions.keyringFile() == null && packOptions.keyringEnv() == null) {
            System.err.println("evidence pack written to " + absolute
                    + " (unsigned — no keyring supplied)");
            return;
        }
        String algo = packOptions.signatureAlgorithm() != null
                ? packOptions.signatureAlgorithm() : "Ed25519 (from keyring)";
        Path signed = command.outputDir().resolve("manifest.sha256.signed");
        if (Files.exists(signed)) {
            System.err.println("evidence pack written to " + absolute
                    + " (signed: " + algo + ")");
        } else {
            System.err.println("evidence pack written to " + absolute
                    + " (WARNING: signing failed — check log)");
        }
    }
}
