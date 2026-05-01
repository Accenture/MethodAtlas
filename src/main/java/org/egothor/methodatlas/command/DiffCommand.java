package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;

import org.egothor.methodatlas.DeltaReport;
import org.egothor.methodatlas.emit.DeltaEmitter;

/**
 * CLI command handler for the {@code -diff} mode.
 *
 * <p>
 * Compares two MethodAtlas scan CSV outputs and emits a delta report showing
 * added, removed, and modified test methods.
 * </p>
 *
 * @see org.egothor.methodatlas.DeltaReport
 * @see org.egothor.methodatlas.emit.DeltaEmitter
 */
public final class DiffCommand implements Command {

    private final Path before;
    private final Path after;

    /**
     * Creates a new diff command.
     *
     * @param before path to the <em>before</em> scan CSV
     * @param after  path to the <em>after</em> scan CSV
     */
    public DiffCommand(Path before, Path after) {
        this.before = before;
        this.after = after;
    }

    /**
     * Computes and emits the delta between the two scan CSV outputs.
     *
     * @param out writer that receives the delta report
     * @return {@code 0} always; errors reading the files propagate as
     *         {@link IOException}
     * @throws IOException if either CSV file cannot be read
     */
    @Override
    public int execute(PrintWriter out) throws IOException {
        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);
        DeltaEmitter.emit(result, out);
        return 0;
    }
}
