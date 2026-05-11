package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.ClassificationOverride;
import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.TestMethodSink;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.emit.JsonEmitter;

/**
 * CLI command handler for the {@code -json} output mode.
 *
 * <p>
 * Scans one or more source roots, buffers all discovered test-method records,
 * and serializes the result as a flat JSON array once the scan completes.
 * </p>
 *
 * <p>
 * The JSON representation differs from CSV in the following ways:
 * </p>
 * <ul>
 * <li>{@code tags} and {@code ai_tags} are JSON arrays, not semicolon-separated
 *     strings</li>
 * <li>Numeric fields are JSON numbers; {@code ai_security_relevant} is a JSON
 *     boolean</li>
 * <li>Optional columns are omitted entirely when the corresponding flag is not
 *     enabled (rather than being left blank)</li>
 * </ul>
 *
 * @see org.egothor.methodatlas.emit.JsonEmitter
 * @see SarifCommand
 * @see ScanCommand
 */
public final class JsonCommand implements Command {

    private static final Logger LOG = Logger.getLogger(JsonCommand.class.getName());

    private final CliConfig cliConfig;
    private final TestDiscoveryConfig discoveryConfig;
    private final AiSuggestionEngine aiEngine;
    private final ClassificationOverride override;
    private final AiResultCache aiCache;

    /**
     * Creates a new JSON command.
     *
     * @param cliConfig       full parsed CLI configuration
     * @param discoveryConfig discovery configuration forwarded to providers
     * @param aiEngine        AI engine providing suggestions; {@code null} when
     *                        AI is disabled
     * @param override        human classification overrides
     * @param aiCache         AI result cache
     */
    public JsonCommand(CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, ClassificationOverride override,
            AiResultCache aiCache) {
        this.cliConfig = cliConfig;
        this.discoveryConfig = discoveryConfig;
        this.aiEngine = aiEngine;
        this.override = override;
        this.aiCache = aiCache;
    }

    /**
     * Scans all roots and emits the buffered result as a JSON array.
     *
     * @param out writer that receives the JSON output
     * @return {@code 0} if all files were processed successfully, {@code 1} if
     *         any file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    @Override
    public int execute(PrintWriter out) throws IOException {
        boolean aiEnabled = aiEngine != null;
        boolean confidenceEnabled = aiEnabled && cliConfig.aiOptions().confidence();
        boolean contentHashEnabled = cliConfig.contentHash();
        boolean emitSourceRoot = cliConfig.emitSourceRoot();
        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();

        JsonEmitter jsonEmitter = new JsonEmitter(aiEnabled, confidenceEnabled, contentHashEnabled,
                cliConfig.driftDetect(), emitSourceRoot);

        List<TestDiscovery> providers = CommandSupport.loadProviders(discoveryConfig);
        boolean hadErrors = false;
        try {
            for (Path root : roots) {
                String sourceRoot = emitSourceRoot ? CommandSupport.computeFilePrefix(List.of(root)) : null;
                final String finalSourceRoot = sourceRoot;
                TestMethodSink rootSink = (fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion) ->
                        jsonEmitter.record(fqcn, method, beginLine, loc, contentHash, tags, displayName,
                                suggestion, finalSourceRoot);
                if (CommandSupport.runDiscovery(root, providers, cliConfig.aiOptions(), aiEngine,
                        CommandSupport.filterSink(rootSink, cliConfig.securityOnly(),
                                cliConfig.minConfidence(), confidenceEnabled),
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

        jsonEmitter.flush(out);
        return hadErrors ? 1 : 0;
    }
}
