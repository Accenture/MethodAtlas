package org.egothor.methodatlas.discovery.cobol.internal;

/**
 * Immutable data transfer object for a discovered COBOL test.
 *
 * @param programId  PROGRAM-ID value extracted from the IDENTIFICATION DIVISION;
 *                   {@code "unknown"} when not found
 * @param name       paragraph name (MFUnit) or TestCase label (COBOL-Check)
 * @param beginLine  1-based line number of the test declaration start
 * @param endLine    1-based line number of the test declaration end
 */
public record MethodInfo(String programId, String name, int beginLine, int endLine) {}
