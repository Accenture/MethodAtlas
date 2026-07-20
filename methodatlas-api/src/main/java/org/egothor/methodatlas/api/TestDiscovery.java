package org.egothor.methodatlas.api;

import java.io.Closeable;
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
 * <li>{@code discovery.jvm} — Java/Kotlin source files with JUnit, TestNG, …</li>
 * <li>{@code discovery.dotnet} — C# source files with xUnit, NUnit, MSTest</li>
 * <li>{@code discovery.typescript} — TypeScript/JavaScript source files with
 *     Jest, Vitest, Mocha, …</li>
 * <li>{@code discovery.go} — Go source files with the {@code testing} package</li>
 * <li>{@code discovery.python} — Python source files with pytest/unittest</li>
 * <li>{@code discovery.powershell} — PowerShell scripts with Pester</li>
 * <li>{@code discovery.abap} — ABAP source with ABAP Unit / ecATT</li>
 * <li>{@code discovery.cobol} — COBOL source test paragraphs</li>
 * </ul>
 *
 * <p>
 * The list above reflects the platforms that ship today; the canonical,
 * up-to-date matrix is maintained in the project README. Adding a language
 * means implementing this interface in a new module — the core needs no change.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Implementations are <strong>not</strong> required to be thread-safe. The
 * orchestration layer calls {@link #configure(TestDiscoveryConfig)} once and
 * then {@link #discover(Path)} from a single thread, and calls
 * {@link #close()} only after discovery has finished; integrators writing their
 * own orchestrator must observe the same single-threaded lifecycle unless an
 * implementation documents otherwise.
 * </p>
 *
 * <p>
 * {@link TestDiscoveryConfig} is deliberately language-neutral: it carries
 * {@link TestDiscoveryConfig#fileSuffixes() fileSuffixes} (universally
 * applicable), {@link TestDiscoveryConfig#testMarkers() testMarkers}
 * (annotation/attribute names for JVM and .NET; unused for TypeScript), and
 * an open-ended {@link TestDiscoveryConfig#properties() properties} map for
 * plugin-specific settings such as test function names ({@code "test"},
 * {@code "it"}) for a Jest/Mocha plugin.
 * </p>
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
 * rather than thrown. {@link #hadErrors()} is <strong>cumulative</strong>: once
 * any {@link #discover} call on this instance encounters such an error it stays
 * {@code true} for the remainder of the instance's lifetime; it is not reset per
 * call. This lets an orchestrator that scans several roots with one provider
 * derive a single overall exit status. Fatal errors (e.g. the root directory
 * cannot be traversed) are propagated as {@link IOException}.
 * </p>
 *
 * <h2>Resource management</h2>
 *
 * <p>
 * Implementations that hold long-lived resources (for example a pool of
 * sub-processes) should override {@link #close} to release those resources.
 * The orchestration layer closes every loaded provider when the scan run
 * finishes.  Implementations that hold no external resources may leave the
 * default no-op {@code close} implementation in place.
 * </p>
 *
 * @see DiscoveredMethod
 * @see TestDiscoveryConfig
 *
 * @since 3.0.0
 */
public interface TestDiscovery extends Closeable {

    /**
     * Returns the unique identifier of this discovery provider.
     *
     * <p>
     * The ID is used by the orchestration layer to route
     * {@link TestDiscoveryConfig#fileSuffixesFor(String)} entries that carry a
     * plugin-specific prefix (e.g. {@code "java:Test.java"} targets only the
     * provider whose {@code pluginId()} returns {@code "java"}).
     * </p>
     *
     * <p>
     * IDs must be unique across all providers present on the classpath.
     * The orchestration layer verifies this at startup and throws
     * {@link IllegalStateException} when two providers share the same ID.
     * </p>
     *
     * <p>
     * Convention: use a short, lowercase, hyphen-separated name that matches
     * the target platform (e.g. {@code "java"}, {@code "dotnet"},
     * {@code "typescript"}).
     * </p>
     *
     * @return non-null, non-empty plugin identifier; must be unique across all
     *         loaded providers
     */
    String pluginId();

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
     * Returns {@code true} if <em>any</em> {@link #discover} call on this
     * instance has encountered at least one non-fatal per-file error.
     *
     * <p>
     * The flag is cumulative for the lifetime of the configured instance: it
     * reflects every error observed since construction / {@link #configure} and
     * is <strong>not</strong> reset between {@link #discover} calls. An
     * orchestrator scanning multiple roots with a single provider therefore sees
     * {@code true} if any root produced an error, which it can map to the process
     * exit status.
     * </p>
     *
     * @return {@code true} when any file could not be processed by this instance
     */
    boolean hadErrors();

    /**
     * Releases any resources held by this provider.
     *
     * <p>
     * The default implementation is a no-op and is suitable for stateless
     * providers that hold no external resources. Implementations that manage
     * long-lived resources (e.g. a pool of sub-processes) must override this
     * method to shut down those resources cleanly.
     * </p>
     *
     * <p>
     * The orchestration layer calls this method once after the last
     * {@link #discover} call has completed.  Providers that register JVM
     * shutdown hooks as a backstop should remove those hooks here to avoid
     * spurious execution after an explicit {@code close()}.
     * </p>
     *
     * @throws IOException if releasing a resource fails
     */
    @Override
    default void close() throws IOException {
        // default: no-op — providers that hold no long-lived resources omit this
    }
}
