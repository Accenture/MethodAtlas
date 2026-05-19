package org.egothor.methodatlas.discovery.powershell.internal;

import java.util.List;

/**
 * Immutable data transfer object for a discovered Pester {@code It} block.
 *
 * @param name      test name from the quoted string argument of the {@code It} command
 * @param tags      tag values from the {@code -Tag} parameter; may be empty
 * @param beginLine 1-based line number of the {@code It} keyword
 * @param endLine   1-based line number of the closing {@code }}
 */
public record CommandInfo(String name, List<String> tags, int beginLine, int endLine) {}
