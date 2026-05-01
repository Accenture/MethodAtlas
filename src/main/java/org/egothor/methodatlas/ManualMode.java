package org.egothor.methodatlas;

import java.nio.file.Path;

/**
 * Selects between the two phases of the manual AI workflow.
 *
 * <p>
 * When a {@link ManualMode} value is present in the parsed configuration the
 * application bypasses the normal automated AI provider path.
 * </p>
 *
 * @see MethodAtlasApp
 */
public sealed interface ManualMode {

    /**
     * Prepare phase: scan source files and write AI prompt work files and empty
     * response stubs.
     *
     * @param workDir     directory where work files ({@code <fqcn>.txt}) will be
     *                    written
     * @param responseDir directory where empty response stubs
     *                    ({@code <fqcn>.response.txt}) will be pre-created; may be
     *                    the same as {@code workDir}
     */
    record Prepare(Path workDir, Path responseDir) implements ManualMode {
    }

    /**
     * Consume phase: read operator-saved response files and emit enriched CSV.
     *
     * @param workDir     directory that contains the work files written during
     *                    prepare (reserved for future reference; currently unused
     *                    at runtime)
     * @param responseDir directory where the operator saved
     *                    {@code <fqcn>.response.txt} files
     */
    record Consume(Path workDir, Path responseDir) implements ManualMode {
    }
}
