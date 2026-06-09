package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.emit.SarifEmitter;

/**
 * CLI command handler for the {@code -sarif} output mode.
 *
 * <p>
 * Scans one or more source roots, buffers all discovered test-method records,
 * and serializes the result as a single SARIF 2.1.0 JSON document once the
 * scan completes. When {@link CliConfig#detectSecrets()} is enabled, credential
 * findings are detected (via {@link CredentialDetectionRunner}) and embedded in the
 * same SARIF document before it is flushed.
 * </p>
 *
 * @see org.egothor.methodatlas.emit.SarifEmitter
 * @see ScanCommand
 * @see GitHubAnnotationsCommand
 */
public final class SarifCommand implements Command {

    private final CliConfig cliConfig;
    private final TestDiscoveryConfig discoveryConfig;
    private final AiSuggestionEngine aiEngine;
    private final ClassificationOverride override;
    private final AiResultCache aiCache;
    private final ScanOrchestrator scanOrchestrator;

    /**
     * Creates a new SARIF command.
     *
     * @param cliConfig         full parsed CLI configuration
     * @param discoveryConfig   discovery configuration forwarded to providers
     * @param aiEngine          AI engine providing suggestions; {@code null}
     *                          when AI is disabled
     * @param override          human classification overrides
     * @param aiCache           AI result cache
     * @param scanOrchestrator  scan-and-emit orchestrator used to process all
     *                          configured roots and buffer SARIF results
     */
    public SarifCommand(CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, ClassificationOverride override,
            AiResultCache aiCache, ScanOrchestrator scanOrchestrator) {
        this.cliConfig = cliConfig;
        this.discoveryConfig = discoveryConfig;
        this.aiEngine = aiEngine;
        this.override = override;
        this.aiCache = aiCache;
        this.scanOrchestrator = scanOrchestrator;
    }

    /**
     * Scans all roots and emits the buffered result as SARIF JSON.
     *
     * @param out writer that receives the serialized SARIF document
     * @return {@code 0} if all files were processed successfully, {@code 1} if
     *         any file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    @Override
    public int execute(PrintWriter out) throws IOException {
        boolean aiEnabled = aiEngine != null;
        boolean confidenceEnabled = aiEnabled && cliConfig.aiOptions().confidence();
        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();

        String filePrefix = ContentHasher.filePrefix(roots);
        boolean scoresInMessage = !cliConfig.sarifOmitScores();
        SarifEmitter sarifEmitter = new SarifEmitter(aiEnabled, confidenceEnabled, filePrefix, scoresInMessage);

        // Credential detection: prepare() folds triage into the per-class call when
        // possible (source sent once) and returns the context the scan threads
        // through; finish() records findings into the SARIF document before flush.
        CredentialDetectionRunner secretRunner = cliConfig.detectSecrets()
                ? new CredentialDetectionRunner(cliConfig, discoveryConfig, new PluginLoader(), scanOrchestrator, aiEngine)
                : null;
        CredentialTriageContext secretCtx = secretRunner != null ? secretRunner.prepare(roots) : null;

        int result = scanOrchestrator.scan(roots, cliConfig, discoveryConfig, aiEngine,
                scanOrchestrator.filterSink(sarifEmitter, cliConfig.securityOnly(),
                        cliConfig.minConfidence(), confidenceEnabled), override, aiCache, secretCtx);

        if (secretRunner != null) {
            secretRunner.finish(roots, sarifEmitter);
        }

        sarifEmitter.flush(out);
        return result;
    }
}
