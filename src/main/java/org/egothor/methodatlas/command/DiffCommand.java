package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.emit.DeltaEmitter;
import org.egothor.methodatlas.emit.DeltaEntry;
import org.egothor.methodatlas.emit.DeltaReport;

/**
 * CLI command handler for the {@code -diff} mode.
 *
 * <p>
 * Compares two MethodAtlas scan CSV outputs and emits a delta report showing
 * added, removed, and modified test methods.
 * </p>
 *
 * <p>
 * When {@code requireJustification} is {@code true}, every security-relevant
 * method that was removed must have a matching entry in the {@code overrideFile}.
 * The command exits with code {@code 1} when any unjustified removal is found.
 * </p>
 *
 * @see org.egothor.methodatlas.emit.DeltaReport
 * @see org.egothor.methodatlas.emit.DeltaEmitter
 */
public final class DiffCommand implements Command {

    private static final int EXIT_GATE_FAILED = 1;

    private final Path before;
    private final Path after;
    private final boolean requireJustification;
    private final Path overrideFile;

    /**
     * Creates a new diff command without the removal gate.
     *
     * @param before path to the <em>before</em> scan CSV
     * @param after  path to the <em>after</em> scan CSV
     */
    public DiffCommand(Path before, Path after) {
        this(before, after, false, null);
    }

    /**
     * Creates a new diff command with optional removal-gate enforcement.
     *
     * @param before               path to the <em>before</em> scan CSV
     * @param after                path to the <em>after</em> scan CSV
     * @param requireJustification when {@code true}, exits non-zero if any
     *                             security-relevant test was removed without a
     *                             matching entry in {@code overrideFile}
     * @param overrideFile         path to a MethodAtlas override YAML supplying
     *                             removal justifications; ignored when
     *                             {@code requireJustification} is {@code false};
     *                             {@code null} means no justifications are on file
     * @since 3.0.0
     */
    public DiffCommand(Path before, Path after, boolean requireJustification, Path overrideFile) {
        this.before = before;
        this.after = after;
        this.requireJustification = requireJustification;
        this.overrideFile = overrideFile;
    }

    /**
     * Computes and emits the delta between the two scan CSV outputs, and
     * optionally enforces the removal-justification gate.
     *
     * @param out writer that receives the delta report
     * @return {@code 0} on success; {@code 1} when the removal gate fires
     * @throws IOException if either CSV file cannot be read
     */
    @Override
    public int execute(PrintWriter out) throws IOException {
        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);
        DeltaEmitter.emit(result, out);
        if (requireJustification) {
            return checkRemovals(result, out);
        }
        return 0;
    }

    /**
     * Checks that every security-relevant removal has a justification in the
     * override file.
     *
     * @param result delta result from the comparison
     * @param out    writer used to print gate-failure details
     * @return {@code 0} when all removals are justified; {@code 1} otherwise
     */
    private int checkRemovals(DeltaReport.DeltaResult result, PrintWriter out) {
        List<DeltaEntry> removals = result.entries().stream()
                .filter(e -> e.changeType() == DeltaEntry.ChangeType.REMOVED)
                .filter(e -> Boolean.TRUE.equals(e.record().aiSecurityRelevant()))
                .toList();

        if (removals.isEmpty()) {
            return 0;
        }

        ClassificationOverride override = new OverrideLoader().load(overrideFile);
        List<DeltaEntry> unjustified = removals.stream()
                .filter(e -> !override.hasOverrideFor(e.record().fqcn(), e.record().method()))
                .toList();

        if (unjustified.isEmpty()) {
            return 0;
        }

        out.println();
        out.println("GATE FAILED: " + unjustified.size()
                + " security-relevant test(s) removed without justification.");
        out.println("Add an entry for each method in an override file and pass it via -override-file:");
        for (DeltaEntry e : unjustified) {
            out.println("  - " + e.record().fqcn() + " : " + e.record().method());
        }
        return EXIT_GATE_FAILED;
    }
}
