/**
 * Java/JVM test discovery implementation for MethodAtlas.
 *
 * <p>
 * This package contains the concrete implementation of
 * {@link org.egothor.methodatlas.api.TestDiscovery} for Java source trees.
 * It walks a directory, parses {@code .java} files with
 * <a href="https://javaparser.org/">JavaParser</a>, and produces a stream of
 * {@link org.egothor.methodatlas.api.DiscoveredMethod} records — one per
 * discovered test method.
 * </p>
 *
 * <h2>ServiceLoader registration</h2>
 *
 * <p>
 * {@link org.egothor.methodatlas.discovery.jvm.JavaTestDiscovery} is
 * registered as a {@link org.egothor.methodatlas.api.TestDiscovery} provider
 * via the service-descriptor file
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.
 * The orchestration layer loads it automatically via
 * {@link java.util.ServiceLoader}, calls
 * {@link org.egothor.methodatlas.api.TestDiscovery#configure} with the
 * current {@link org.egothor.methodatlas.api.TestDiscoveryConfig}, and then
 * invokes {@link org.egothor.methodatlas.api.TestDiscovery#discover} for each
 * configured source root.
 * </p>
 *
 * <p>
 * To add support for another language (e.g. TypeScript, C#), create a new
 * sub-package under {@code org.egothor.methodatlas.discovery}, implement
 * {@link org.egothor.methodatlas.api.TestDiscovery}, and register it in the
 * provider JAR's {@code META-INF/services} file.  No changes to the core
 * application are required.
 * </p>
 *
 * <h2>Components</h2>
 *
 * <ul>
 * <li>{@link org.egothor.methodatlas.discovery.jvm.JavaTestDiscovery} —
 *     implements {@link org.egothor.methodatlas.api.TestDiscovery}; walks a
 *     source root, filters files by configurable name suffixes, and emits
 *     one {@link org.egothor.methodatlas.api.DiscoveredMethod} per test
 *     method found in a matching class.  Supports a no-arg constructor for
 *     {@link java.util.ServiceLoader} use as well as a parameterised
 *     constructor for programmatic/test use.</li>
 * <li>{@link org.egothor.methodatlas.discovery.jvm.AnnotationInspector} —
 *     stateless utility that detects JUnit test annotations
 *     ({@code @Test}, {@code @ParameterizedTest}, etc.), extracts
 *     {@code @Tag} values and {@code @DisplayName} text, and measures
 *     method line counts.</li>
 * </ul>
 *
 * @see org.egothor.methodatlas.api.TestDiscovery
 * @see org.egothor.methodatlas.api.TestDiscoveryConfig
 * @see org.egothor.methodatlas.api.DiscoveredMethod
 */
package org.egothor.methodatlas.discovery.jvm;
