package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.ClassificationOverride;
import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * CLI command handler for the {@code -apply-tags} mode.
 *
 * <p>
 * Discovers test methods via the configured {@link TestDiscovery} providers,
 * resolves AI suggestions for each class, and delegates the actual source file
 * write-back to the matching {@link SourcePatcher} implementation. A summary
 * line is written to the supplied writer on completion.
 * </p>
 *
 * @see ApplyTagsFromCsvCommand
 * @see org.egothor.methodatlas.api.SourcePatcher
 */
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
public final class ApplyTagsCommand implements Command {

    private static final Logger LOG = Logger.getLogger(ApplyTagsCommand.class.getName());

    private final CliConfig cliConfig;
    private final TestDiscoveryConfig discoveryConfig;
    private final AiSuggestionEngine aiEngine;
    private final ClassificationOverride override;
    private final AiResultCache aiCache;

    /**
     * Creates a new apply-tags command.
     *
     * @param cliConfig       full parsed CLI configuration
     * @param discoveryConfig discovery configuration forwarded to providers
     * @param aiEngine        AI engine providing suggestions; {@code null} when
     *                        AI is disabled
     * @param override        human classification overrides
     * @param aiCache         AI result cache
     */
    public ApplyTagsCommand(CliConfig cliConfig, TestDiscoveryConfig discoveryConfig,
            AiSuggestionEngine aiEngine, ClassificationOverride override,
            AiResultCache aiCache) {
        this.cliConfig = cliConfig;
        this.discoveryConfig = discoveryConfig;
        this.aiEngine = aiEngine;
        this.override = override;
        this.aiCache = aiCache;
    }

    /**
     * Discovers test methods, gathers AI suggestions, and patches source files.
     *
     * @param out writer for the completion summary line
     * @return {@code 0} if all files were processed successfully, {@code 1}
     *         if any file produced a parse or processing error
     * @throws IOException if traversing a file tree fails
     */
    @Override
    public int execute(PrintWriter out) throws IOException {
        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();
        List<SourcePatcher> patchers = CommandSupport.loadPatchers(discoveryConfig);
        List<TestDiscovery> providers = CommandSupport.loadProviders(discoveryConfig);

        // Initializers are omitted: both variables are assigned unconditionally in the
        // try block, which satisfies JLS §16.2.15 definite-assignment for try-finally
        // without a catch clause.
        Map<Path, List<DiscoveredMethod>> byFile;
        boolean hadErrors;
        try {
            byFile = CommandSupport.collectMethodsByFile(roots, providers);
            hadErrors = providers.stream().anyMatch(TestDiscovery::hadErrors);
        } finally {
            CommandSupport.closeAll(providers);
        }

        CommandSupport.AiRuntime ai =
                new CommandSupport.AiRuntime(cliConfig.aiOptions(), aiEngine, override, aiCache);

        int modifiedFiles = 0;
        int totalAnnotations = 0;

        for (Map.Entry<Path, List<DiscoveredMethod>> entry : byFile.entrySet()) {
            Path sourceFile = entry.getKey();
            List<DiscoveredMethod> methods = entry.getValue();

            SourcePatcher patcher = patchers.stream()
                    .filter(p -> p.supports(sourceFile))
                    .findFirst().orElse(null);
            if (patcher == null) {
                continue;
            }

            Map<String, List<DiscoveredMethod>> byClass = methods.stream()
                    .collect(Collectors.groupingBy(DiscoveredMethod::fqcn,
                            LinkedHashMap::new, Collectors.toList()));

            Map<String, List<String>> tagsToApply = new LinkedHashMap<>();
            Map<String, String> displayNames = new LinkedHashMap<>();

            CommandSupport.gatherAiSuggestionsForFile(byClass, ai, aiCache, tagsToApply, displayNames);

            if (!tagsToApply.isEmpty() || !displayNames.isEmpty()) {
                try {
                    int changes = patcher.patch(sourceFile, tagsToApply, displayNames, out);
                    if (changes > 0) {
                        modifiedFiles++;
                        totalAnnotations += changes;
                    }
                } catch (IOException e) {
                    if (LOG.isLoggable(Level.WARNING)) {
                        LOG.log(Level.WARNING, "Cannot process: " + sourceFile, e);
                    }
                    hadErrors = true;
                }
            }
        }

        out.println("Apply-tags complete: " + totalAnnotations + " annotation(s) added to "
                + modifiedFiles + " file(s)");
        return hadErrors ? 1 : 0;
    }
}
