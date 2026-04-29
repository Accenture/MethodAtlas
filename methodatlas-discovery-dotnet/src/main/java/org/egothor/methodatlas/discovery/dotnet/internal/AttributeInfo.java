package org.egothor.methodatlas.discovery.dotnet.internal;

import java.util.List;
import java.util.Map;

/**
 * Parsed representation of a single C# attribute, e.g.
 * {@code [Category("security")]} or {@code [Fact(DisplayName = "my test")]}.
 *
 * @param simpleName       unqualified attribute name (last segment of
 *                         a dotted name), e.g. {@code "Category"}
 * @param positionalArgs   string literal values of positional arguments;
 *                         non-string arguments produce a {@code null} entry
 * @param namedArgs        string literal values of named arguments keyed by
 *                         argument name; non-string values are omitted
 * @param sectionStartLine 1-based start line of the enclosing {@code [...]}
 *                         attribute section in the source file
 * @param sectionStopLine  1-based stop line of the enclosing attribute section
 */
public record AttributeInfo(
        String simpleName,
        List<String> positionalArgs,
        Map<String, String> namedArgs,
        int sectionStartLine,
        int sectionStopLine) {
}
