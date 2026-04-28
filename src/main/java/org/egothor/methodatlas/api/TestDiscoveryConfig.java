package org.egothor.methodatlas.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runtime configuration supplied to a {@link TestDiscovery} provider when it
 * is loaded via {@link java.util.ServiceLoader}.
 *
 * <p>
 * An instance is built from the parsed command-line options and passed to
 * {@link TestDiscovery#configure} before the first call to
 * {@link TestDiscovery#discover}. Providers use the values to set up
 * file-selection and test-identification behaviour specific to their target
 * language or test framework.
 * </p>
 *
 * <h2>Fields</h2>
 *
 * <ul>
 * <li>{@link #fileSuffixes} — file-name suffixes that select source files to
 *     parse (e.g. {@code ["Test.java"]}, {@code [".test.ts"]}, {@code [".cs"]}).
 *     Providers are free to use or ignore this hint.</li>
 * <li>{@link #testMarkers} — language-neutral identifiers that mark test
 *     methods.  The semantics are provider-defined: for JVM providers these are
 *     annotation simple names ({@code "Test"}, {@code "ParameterizedTest"});
 *     for .NET providers they are attribute names ({@code "Fact"},
 *     {@code "Test"}); for TypeScript providers this set is typically empty
 *     (test functions are identified by name rather than annotation).
 *     An empty set means "use provider defaults".</li>
 * <li>{@link #properties} — arbitrary key/value pairs for provider-specific
 *     settings that cannot be captured by {@code fileSuffixes} or
 *     {@code testMarkers}.  Examples: {@code functionNames → ["test", "it"]}
 *     for a Jest/Mocha plugin, {@code traitFilters → ["Category=Security"]}
 *     for a .NET NUnit plugin.  Providers ignore keys they do not recognise.
 *     An empty map means "use provider defaults" for all plugin-specific
 *     settings.</li>
 * </ul>
 *
 * @param fileSuffixes file-name suffixes used to select source files;
 *                     never {@code null}; may be empty
 * @param testMarkers  language-neutral identifiers that mark test methods;
 *                     never {@code null}; empty means "use provider
 *                     defaults / auto-detect"
 * @param properties   plugin-specific key/multi-value pairs; never
 *                     {@code null}; empty means "use provider defaults"
 *
 * @see TestDiscovery#configure
 * @see TestDiscovery
 */
public record TestDiscoveryConfig(
        List<String> fileSuffixes,
        Set<String> testMarkers,
        Map<String, List<String>> properties) {

    /**
     * Compact constructor that defensively copies all three collections.
     *
     * <p>
     * {@code properties} is deep-copied: each inner list is made unmodifiable
     * before the outer map is made unmodifiable, so callers cannot mutate
     * per-key value lists through a retained reference.
     * </p>
     *
     * @param fileSuffixes file-name suffixes; copied to an unmodifiable list
     * @param testMarkers  test-marker identifiers; copied to an unmodifiable set
     * @param properties   plugin-specific entries; deep-copied to an
     *                     unmodifiable map of unmodifiable lists
     */
    public TestDiscoveryConfig {
        fileSuffixes = List.copyOf(fileSuffixes);
        testMarkers = Set.copyOf(testMarkers);
        properties = properties.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> List.copyOf(e.getValue())));
    }
}
