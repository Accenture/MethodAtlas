package org.egothor.methodatlas.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Source of discovered test methods for a specific programming language and
 * test framework.
 *
 * <p>
 * Implementations scan a directory tree and emit one {@link DiscoveredMethod}
 * per test method found. The orchestration layer ({@code MethodAtlasApp})
 * programs against this interface; it has no knowledge of how test methods
 * are identified in any particular language or framework.
 * </p>
 *
 * <h2>Platform support model</h2>
 *
 * <p>
 * Each platform has its own implementation in a dedicated sub-package:
 * </p>
 * <ul>
 * <li>{@code discovery.jvm} — Java source files with JUnit, TestNG, …</li>
 * <li>{@code discovery.dotnet} — (future) C# source files with xUnit, NUnit, …</li>
 * <li>{@code discovery.typescript} — (future) TypeScript source files with
 *     Jest, Vitest, Mocha, …</li>
 * </ul>
 *
 * <h2>ServiceLoader integration</h2>
 *
 * <p>
 * Providers are discovered via {@link java.util.ServiceLoader}.  Each provider
 * JAR ships a
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}
 * registration file listing its implementation class.  The orchestration layer
 * loads all available providers, calls {@link #configure} on each with the
 * current {@link TestDiscoveryConfig}, and then runs all providers against
 * every scan root, merging their result streams.  This means placing multiple
 * provider JARs on the classpath automatically enables multi-language scanning.
 * </p>
 *
 * <h2>Error handling</h2>
 *
 * <p>
 * Non-fatal per-file errors (e.g. parse failures) should be logged and skipped
 * rather than thrown. {@link #hadErrors()} returns {@code true} after a
 * {@link #discover} call that encountered at least one such error. Fatal errors
 * (e.g. the root directory cannot be traversed) are propagated as
 * {@link IOException}.
 * </p>
 *
 * @see DiscoveredMethod
 * @see TestDiscoveryConfig
 */
public interface TestDiscovery {

    /**
     * Configures this provider before the first call to {@link #discover}.
     *
     * <p>
     * The orchestration layer calls this method exactly once after loading the
     * provider via {@link java.util.ServiceLoader} and before any call to
     * {@link #discover}.  Providers that need no runtime configuration may
     * leave this as the default no-op.
     * </p>
     *
     * <p>
     * Providers loaded programmatically (e.g. in tests) may also call this
     * method to (re-)configure an existing instance, or use a constructor that
     * accepts the same information directly.
     * </p>
     *
     * @param config runtime configuration supplied by the calling application;
     *               never {@code null}
     */
    default void configure(TestDiscoveryConfig config) {
        // default: no-op — providers that need no runtime configuration omit this
    }

    /**
     * Scans {@code root} and returns a stream of discovered test methods.
     *
     * <p>
     * The stream is fully materialized before being returned; it is safe to
     * call this method multiple times (e.g. once per scan root).
     * </p>
     *
     * @param root directory to scan
     * @return stream of discovered test methods; never {@code null}
     * @throws IOException if traversing the file tree fails
     */
    Stream<DiscoveredMethod> discover(Path root) throws IOException;

    /**
     * Returns {@code true} if the most recent {@link #discover} call (or any
     * prior call) encountered at least one non-fatal per-file error.
     *
     * @return {@code true} when any file could not be processed
     */
    boolean hadErrors();
}
