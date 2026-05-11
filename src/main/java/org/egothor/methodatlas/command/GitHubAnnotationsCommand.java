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
import org.egothor.methodatlas.emit.GitHubAnnotationsEmitter;

/**
 * CLI command handler for the {@code -github-annotations} output mode.
 *
 * <p>
 * Scans one or more source roots and emits GitHub Actions {@code ::notice} and
 * {@code ::warning} workflow commands for security-relevant test methods.
 * </p>
 *
 * @see org.egothor.methodatlas.emit.GitHubAnnotationsEmitter
 * @see SarifCommand
 * @see ScanCommand
 */
public final class GitHubAnnotationsCommand implements Command {

    private final CliConfig cliConfig;
    private final TestDiscoveryConfig discoveryConfig;
    private final AiSuggestionEngine aiEngine;
    private final ClassificationOverride override;
    private final AiResultCache aiCache;

    /**
     * Creates a new GitHub Annotations command.
     *
     * @param cliConfig       full parsed CLI configuration
     * @param discoveryConfig discovery configuration forwarded to providers
     * @param aiEngine        AI engine providing suggestions; {@code null} when
     *                        AI is disabled
     * @param override        human classification overrides
     * @param aiCache         AI result cache
     */
    public GitHubAnnotationsCommand(CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, ClassificationOverride override,
            AiResultCache aiCache) {
        this.cliConfig = cliConfig;
        this.discoveryConfig = discoveryConfig;
        this.aiEngine = aiEngine;
        this.override = override;
        this.aiCache = aiCache;
    }

    /**
     * Scans all roots and emits GitHub Actions workflow commands.
     *
     * @param out writer that receives the workflow command lines
     * @return {@code 0} if all files were processed successfully, {@code 1} if
     *         any file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    @Override
    public int execute(PrintWriter out) throws IOException {
        boolean confidenceEnabled = cliConfig.aiOptions().confidence();
        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();
        String filePrefix = CommandSupport.computeFilePrefix(roots);
        GitHubAnnotationsEmitter emitter = new GitHubAnnotationsEmitter(out, filePrefix);
        return CommandSupport.scan(roots, cliConfig, discoveryConfig, aiEngine,
                CommandSupport.filterSink(emitter, false, cliConfig.minConfidence(), confidenceEnabled),
                override, aiCache);
    }
}
