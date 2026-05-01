package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.ClassificationOverride;
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
 * scan completes.
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

    /**
     * Creates a new SARIF command.
     *
     * @param cliConfig       full parsed CLI configuration
     * @param discoveryConfig discovery configuration forwarded to providers
     * @param aiEngine        AI engine providing suggestions; {@code null} when
     *                        AI is disabled
     * @param override        human classification overrides
     * @param aiCache         AI result cache
     */
    public SarifCommand(CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, ClassificationOverride override,
            AiResultCache aiCache) {
        this.cliConfig = cliConfig;
        this.discoveryConfig = discoveryConfig;
        this.aiEngine = aiEngine;
        this.override = override;
        this.aiCache = aiCache;
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

        String filePrefix = CommandSupport.computeFilePrefix(roots);
        boolean scoresInMessage = !cliConfig.sarifOmitScores();
        SarifEmitter sarifEmitter = new SarifEmitter(aiEnabled, confidenceEnabled, filePrefix, scoresInMessage);

        int result = CommandSupport.scan(roots, cliConfig, discoveryConfig, aiEngine,
                CommandSupport.filterSink(sarifEmitter, cliConfig.securityOnly()), override, aiCache);
        sarifEmitter.flush(out);
        return result;
    }
}
