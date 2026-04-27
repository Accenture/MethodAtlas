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
 * <h2>Components</h2>
 *
 * <ul>
 * <li>{@link org.egothor.methodatlas.discovery.jvm.JavaTestDiscovery} —
 *     implements {@link org.egothor.methodatlas.api.TestDiscovery}; walks a
 *     source root, filters files by configurable name suffixes, and emits
 *     one {@link org.egothor.methodatlas.api.DiscoveredMethod} per test
 *     method found in a matching class.</li>
 * <li>{@link org.egothor.methodatlas.discovery.jvm.AnnotationInspector} —
 *     stateless utility that detects JUnit test annotations
 *     ({@code @Test}, {@code @ParameterizedTest}, etc.), extracts
 *     {@code @Tag} values and {@code @DisplayName} text, and measures
 *     method line counts.</li>
 * </ul>
 *
 * @see org.egothor.methodatlas.api.TestDiscovery
 * @see org.egothor.methodatlas.api.DiscoveredMethod
 */
package org.egothor.methodatlas.discovery.jvm;
