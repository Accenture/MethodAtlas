package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * A self-contained CLI command handler.
 *
 * <p>
 * Each implementation encapsulates one logical CLI mode (scan, diff,
 * apply-tags, etc.). The implementation is responsible for all orchestration
 * steps needed by that mode and writes its output to the supplied
 * {@link PrintWriter}.
 * </p>
 *
 * <p>
 * Command objects are constructed with all parameters they need — configuration,
 * pre-built engine instances, I/O paths — and executed via {@link #execute}.
 * The {@link org.egothor.methodatlas.MethodAtlasApp} routing layer selects the
 * right implementation based on the parsed command-line flags, then delegates
 * to it.
 * </p>
 *
 * @see org.egothor.methodatlas.MethodAtlasApp
 * @see CommandSupport
 */
@FunctionalInterface
public interface Command {

    /**
     * Executes this command and returns an exit code.
     *
     * @param out writer that receives all output produced by this command
     * @return {@code 0} on success; {@code 1} when one or more files could not
     *         be processed or a configured limit was exceeded
     * @throws IOException if I/O fails in a way that prevents the command from
     *                     completing
     */
    int execute(PrintWriter out) throws IOException;
}
