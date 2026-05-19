package org.egothor.methodatlas.discovery.go.internal;

/**
 * Immutable data transfer object for a discovered Go test function.
 *
 * @param name      function name (e.g. {@code TestLoginValid})
 * @param beginLine 1-based line number of the {@code func} keyword
 * @param endLine   1-based line number of the closing {@code }}
 */
public record MethodInfo(String name, int beginLine, int endLine) {}
