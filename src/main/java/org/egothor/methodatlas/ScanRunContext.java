// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas;

import java.util.Optional;

/**
 * Thread-local holder for the current {@link ScanRun}, the JUL-friendly
 * equivalent of SLF4J's MDC.
 *
 * <p>
 * {@link MethodAtlasApp#run(String[], java.io.PrintWriter)} constructs a
 * {@link ScanRun} once at the top of every invocation and calls
 * {@link #set(ScanRun)} so that any code executed on the same thread for
 * the duration of the run can read the correlation id through
 * {@link #current()}. A custom {@code java.util.logging.Formatter} reads
 * the context and prepends the run id to every log record (introduced in
 * Item 20 of the architecture remediation plan).
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Each thread sees its own value. The MethodAtlas CLI runs single-threaded
 * scans by default; AI provider calls are sequential. When concurrent
 * threads do appear (the {@link java.util.ServiceLoader} per-plugin
 * isolation), callers that want the run id on those threads must propagate
 * it explicitly.
 * </p>
 *
 * <h2>Cleanup</h2>
 *
 * <p>
 * Always pair {@link #set(ScanRun)} with a {@link #clear()} in a
 * {@code finally} block — without it, the thread-local reference outlives
 * the CLI invocation in container deployments that pool threads. The
 * standard MethodAtlas CLI exits the JVM at end-of-run, so cleanup matters
 * mainly when MethodAtlas is invoked programmatically.
 * </p>
 *
 * @since 1.0.0
 */
public final class ScanRunContext {

    private static final ThreadLocal<ScanRun> CURRENT = new ThreadLocal<>();

    private ScanRunContext() {
        // Utility class -- instantiation makes no sense.
    }

    /**
     * Sets the current scan run for the calling thread.
     *
     * @param run the run identifier; must not be {@code null}
     */
    public static void set(ScanRun run) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null; call clear() to remove the context");
        }
        CURRENT.set(run);
    }

    /**
     * Returns the current scan run for the calling thread, or
     * {@link Optional#empty()} when no run is currently set (the standard
     * case before {@link MethodAtlasApp#main(String[])} runs or after
     * {@link #clear()}).
     *
     * @return optional carrying the current run
     */
    public static Optional<ScanRun> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Removes the current scan run from the calling thread. Always called in
     * a {@code finally} block paired with {@link #set(ScanRun)} so that the
     * thread-local reference does not outlive the CLI invocation.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
