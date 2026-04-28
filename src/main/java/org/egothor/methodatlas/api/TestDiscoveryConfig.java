package org.egothor.methodatlas.api;

import java.util.List;
import java.util.Set;

/**
 * Runtime configuration supplied to a {@link TestDiscovery} provider when it
 * is loaded via {@link java.util.ServiceLoader}.
 *
 * <p>
 * An instance is built from the parsed command-line options and passed to
 * {@link TestDiscovery#configure} before the first call to
 * {@link TestDiscovery#discover}. Providers use the values to set up
 * file-selection and annotation-detection behaviour that is specific to their
 * target language or test framework.
 * </p>
 *
 * <h2>Fields</h2>
 *
 * <ul>
 * <li>{@link #fileSuffixes} — file-name suffixes that select source files to
 *     parse (e.g. {@code ["Test.java"]}).  Providers are free to use or ignore
 *     this hint; a .NET provider may, for example, always select {@code *.cs}
 *     files regardless.</li>
 * <li>{@link #testAnnotations} — simple annotation names that identify test
 *     methods (e.g. {@code "Test"}, {@code "ParameterizedTest"}).  An empty
 *     set means "use provider defaults" (automatic framework detection for the
 *     JVM provider).</li>
 * </ul>
 *
 * @param fileSuffixes    file-name suffixes used to select source files;
 *                        never {@code null}; may be empty
 * @param testAnnotations annotation simple names that mark test methods;
 *                        never {@code null}; empty means "use provider
 *                        defaults / auto-detect"
 *
 * @see TestDiscovery#configure
 * @see TestDiscovery
 */
public record TestDiscoveryConfig(List<String> fileSuffixes, Set<String> testAnnotations) {

    /**
     * Compact constructor that defensively copies both collections.
     *
     * @param fileSuffixes    file-name suffixes; copied to an unmodifiable list
     * @param testAnnotations annotation simple names; copied to an unmodifiable set
     */
    public TestDiscoveryConfig {
        fileSuffixes = List.copyOf(fileSuffixes);
        testAnnotations = Set.copyOf(testAnnotations);
    }
}
