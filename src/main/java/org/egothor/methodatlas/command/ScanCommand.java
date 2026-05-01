package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.ClassificationOverride;
import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.OutputMode;
import org.egothor.methodatlas.TestMethodSink;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.emit.OutputEmitter;

/**
 * CLI command handler for the default CSV and {@code -plain} output modes.
 *
 * <p>
 * Scans one or more source roots, optionally enriches the output with AI
 * suggestions, and emits test-method records incrementally to the supplied
 * writer.
 * </p>
 *
 * @see org.egothor.methodatlas.emit.OutputEmitter
 * @see SarifCommand
 * @see GitHubAnnotationsCommand
 */
public final class ScanCommand implements Command {

    private static final Logger LOG = Logger.getLogger(ScanCommand.class.getName());

    private final CliConfig cliConfig;
    private final TestDiscoveryConfig discoveryConfig;
    private final AiSuggestionEngine aiEngine;
    private final ClassificationOverride override;
    private final AiResultCache aiCache;

    /**
     * Creates a new scan command.
     *
     * @param cliConfig       full parsed CLI configuration
     * @param discoveryConfig discovery configuration forwarded to providers
     * @param aiEngine        AI engine providing suggestions; {@code null} when
     *                        AI is disabled
     * @param override        human classification overrides
     * @param aiCache         AI result cache
     */
    public ScanCommand(CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, ClassificationOverride override,
            AiResultCache aiCache) {
        this.cliConfig = cliConfig;
        this.discoveryConfig = discoveryConfig;
        this.aiEngine = aiEngine;
        this.override = override;
        this.aiCache = aiCache;
    }

    /**
     * Runs the scan and emits output incrementally.
     *
     * @param out writer that receives all emitted output
     * @return {@code 0} if all files were processed successfully, {@code 1} if
     *         any file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    @Override
    public int execute(PrintWriter out) throws IOException {
        boolean aiEnabled = aiEngine != null;
        boolean confidenceEnabled = aiEnabled && cliConfig.aiOptions().confidence();
        boolean contentHashEnabled = cliConfig.contentHash();
        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();

        OutputEmitter emitter = new OutputEmitter(out, aiEnabled, confidenceEnabled, contentHashEnabled,
                cliConfig.driftDetect(), cliConfig.emitSourceRoot());

        if (cliConfig.emitMetadata()) {
            String version = ScanCommand.class.getPackage().getImplementationVersion();
            String taxonomyInfo = CommandSupport.resolveTaxonomyInfo(cliConfig.aiOptions(), aiEnabled);
            emitter.emitMetadata(version != null ? version : "dev", Instant.now().toString(), taxonomyInfo);
        }

        emitter.emitCsvHeader(cliConfig.outputMode());

        final OutputMode mode = cliConfig.outputMode();
        final boolean emitSourceRoot = cliConfig.emitSourceRoot();

        // Scan each root with its own sink so the source_root value can be captured
        // per root. When emitSourceRoot is false, sourceRoot is null and the column
        // is omitted from the output.
        List<TestDiscovery> providers = CommandSupport.loadProviders(discoveryConfig);
        boolean hadErrors = false;
        try {
            for (Path root : roots) {
                String sourceRoot = emitSourceRoot ? CommandSupport.computeFilePrefix(List.of(root)) : null;
                TestMethodSink rootSink = (fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion) ->
                        emitter.emit(mode, fqcn, method, loc, contentHash, tags, displayName, suggestion, sourceRoot);
                if (CommandSupport.runDiscovery(root, providers, cliConfig.aiOptions(), aiEngine,
                        CommandSupport.filterSink(rootSink, cliConfig.securityOnly()),
                        cliConfig.contentHash(), override, aiCache)) {
                    hadErrors = true;
                }
            }
        } finally {
            CommandSupport.closeAll(providers);
        }

        if (aiCache.isActive() && LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "AI cache: {0} hit(s), {1} miss(es)",
                    new Object[] { aiCache.hits(), aiCache.misses() });
        }
        return hadErrors ? 1 : 0;
    }
}
