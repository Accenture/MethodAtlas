package org.egothor.methodatlas.emit;

import java.io.PrintWriter;

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;

/**
 * Fixes the line terminator of structural scan output to {@code \n}.
 *
 * <p>
 * {@link PrintWriter#println(String)} terminates a line with
 * {@link System#lineSeparator()}, which is {@code \r\n} on Windows and
 * {@code \n} elsewhere; Jackson's default pretty printer indents with the same
 * system separator. Structural scan output is a reproducibility artefact (it
 * is hashed, diffed and compared across machines), so it must be
 * byte-identical on every operating system. Emitters therefore write lines
 * through {@link #println(PrintWriter, String)} and configure their JSON
 * mappers with {@link #prettyPrinter()}.
 * </p>
 *
 * <p>
 * Stateless and thread-safe; the writer's own thread-safety rules apply.
 * </p>
 */
final class LineEnding {

    /** The line terminator used by every structural emitter. */
    /* default */ static final char LF = '\n';

    private static final String INDENT = "  ";

    private LineEnding() {
        // static helper; no instances
    }

    /**
     * Writes {@code line} followed by {@code \n}, regardless of platform.
     *
     * @param out  destination writer; must not be {@code null}
     * @param line text to write, without a terminator; must not be {@code null}
     */
    /* default */ static void println(final PrintWriter out, final String line) {
        out.print(line);
        out.print(LF);
        out.flush();
    }

    /**
     * Creates a Jackson pretty printer that matches the default layout (two-space
     * indent, inline arrays) but breaks object lines with {@code \n} instead of
     * the platform line separator.
     *
     * @return a new printer instance; never {@code null}
     */
    /* default */ static DefaultPrettyPrinter prettyPrinter() {
        return new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter(INDENT, String.valueOf(LF)));
    }
}
