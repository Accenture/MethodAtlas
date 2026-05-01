package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.ManualMode;
import org.egothor.methodatlas.ai.AiSuggestionException;
import org.egothor.methodatlas.ai.ManualPrepareEngine;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * CLI command handler for the {@code -manual-prepare} mode.
 *
 * <p>
 * Discovers test classes via the configured {@link TestDiscovery} providers,
 * then writes one work file per class to the prepare work directory.
 * No CSV output is produced; only progress lines are written to the
 * supplied {@link PrintWriter}.
 * </p>
 *
 * @see org.egothor.methodatlas.ai.ManualPrepareEngine
 * @see org.egothor.methodatlas.ai.ManualConsumeEngine
 */
public final class ManualPrepareCommand implements Command {

    private static final Logger LOG = Logger.getLogger(ManualPrepareCommand.class.getName());

    private final ManualMode.Prepare prepare;
    private final CliConfig cliConfig;
    private final TestDiscoveryConfig discoveryConfig;

    /**
     * Creates a new manual-prepare command.
     *
     * @param prepare         manual prepare mode configuration (work and response dirs)
     * @param cliConfig       full parsed CLI configuration
     * @param discoveryConfig discovery configuration forwarded to providers
     */
    public ManualPrepareCommand(ManualMode.Prepare prepare, CliConfig cliConfig,
            TestDiscoveryConfig discoveryConfig) {
        this.prepare = prepare;
        this.cliConfig = cliConfig;
        this.discoveryConfig = discoveryConfig;
    }

    /**
     * Discovers test classes, writes work files, and reports progress.
     *
     * @param out writer for progress and summary output
     * @return {@code 0} if all files were processed successfully, {@code 1} if any
     *         provider encountered a processing error
     * @throws IOException if traversing a file tree fails
     */
    @Override
    @SuppressWarnings("PMD.CloseResource") // providers closed by closeAll() in the finally block
    public int execute(PrintWriter out) throws IOException {
        ManualPrepareEngine engine;
        try {
            engine = new ManualPrepareEngine(prepare.workDir(), prepare.responseDir(), cliConfig.aiOptions());
        } catch (AiSuggestionException e) {
            throw new IllegalStateException("Failed to initialize manual prepare engine", e);
        }

        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();
        List<TestDiscovery> providers = CommandSupport.loadProviders(discoveryConfig);
        boolean hadErrors = false;
        int prepared = 0;

        try {
            for (Path root : roots) {
                List<DiscoveredMethod> allMethods = new ArrayList<>(); // NOPMD – intentionally one list per scan root
                for (TestDiscovery provider : providers) {
                    provider.discover(root).forEach(allMethods::add);
                    if (provider.hadErrors()) {
                        hadErrors = true;
                    }
                }

                // Group by FQCN so each class produces one work file.
                Map<String, List<DiscoveredMethod>> byClass = allMethods.stream()
                        .collect(Collectors.groupingBy(DiscoveredMethod::fqcn,
                                LinkedHashMap::new, Collectors.toList()));

                for (Map.Entry<String, List<DiscoveredMethod>> entry : byClass.entrySet()) {
                    String fqcn = entry.getKey();
                    List<DiscoveredMethod> classMethods = entry.getValue();
                    String classSource = classMethods.get(0).sourceContent().get().orElse(null);
                    if (classSource == null) {
                        continue;
                    }
                    String fileStem = classMethods.get(0).fileStem();
                    List<PromptBuilder.TargetMethod> targetMethods = classMethods.stream()
                            .map(CommandSupport::toTargetMethod).toList();
                    try {
                        Path workFile = engine.prepare(fileStem, fqcn, classSource, targetMethods);
                        out.println("Prepared: " + workFile);
                        prepared++;
                    } catch (AiSuggestionException e) {
                        if (LOG.isLoggable(Level.WARNING)) {
                            LOG.log(Level.WARNING, "Failed to prepare work file for " + fqcn, e);
                        }
                    }
                }
            }
        } finally {
            CommandSupport.closeAll(providers);
        }

        out.println("Manual prepare complete. Wrote " + prepared + " work file(s) to " + prepare.workDir()
                + " (response stubs in " + prepare.responseDir() + ")");
        return hadErrors ? 1 : 0;
    }
}
