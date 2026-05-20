package org.egothor.methodatlas.discovery.abap.internal;

/**
 * Immutable data transfer object for a discovered ABAP test or ecATT function.
 *
 * @param className  owning class name for ABAP Unit methods; empty string for
 *                   ecATT function blocks
 * @param name       method name (ABAP Unit) or function name (ecATT)
 * @param beginLine  1-based line number of the declaration start
 * @param endLine    1-based line number of the declaration end
 */
public record MethodInfo(String className, String name, int beginLine, int endLine) {}
