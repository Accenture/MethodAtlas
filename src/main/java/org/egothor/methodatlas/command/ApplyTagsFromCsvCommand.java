package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.egothor.methodatlas.ApplyTagsFromCsvEngine;
import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * CLI command handler for the {@code -apply-tags-from-csv} mode.
 *
 * <p>
 * Applies the annotation decisions recorded in a reviewed CSV back to the
 * source files. The CSV is treated as a complete desired-state specification:
 * every test method's {@code @Tag} set and {@code @DisplayName} are driven
 * entirely by the corresponding CSV row.
 * </p>
 *
 * @see ApplyTagsCommand
 * @see org.egothor.methodatlas.ApplyTagsFromCsvEngine
 */
public final class ApplyTagsFromCsvCommand implements Command {

    private final CliConfig cliConfig;
    private final TestDiscoveryConfig discoveryConfig;

    /**
     * Creates a new apply-tags-from-csv command.
     *
     * @param cliConfig       full parsed CLI configuration
     * @param discoveryConfig discovery configuration forwarded to patchers
     */
    public ApplyTagsFromCsvCommand(CliConfig cliConfig, TestDiscoveryConfig discoveryConfig) {
        this.cliConfig = cliConfig;
        this.discoveryConfig = discoveryConfig;
    }

    /**
     * Applies annotation decisions from the reviewed CSV to source files.
     *
     * @param out writer for progress and summary output
     * @return {@code 0} on success, {@code 1} when the mismatch limit is
     *         exceeded or a fatal error occurs
     * @throws IOException if the CSV or source files cannot be read or written
     */
    @Override
    public int execute(PrintWriter out) throws IOException {
        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();
        List<SourcePatcher> patchers = CommandSupport.loadPatchers(discoveryConfig);
        return ApplyTagsFromCsvEngine.apply(
                cliConfig.applyTagsFromCsvFile(),
                roots,
                cliConfig.mismatchLimit(),
                patchers,
                out);
    }
}
