package org.egothor.methodatlas.discovery.dotnet.internal;

import java.util.List;

/**
 * Structural information about a single C# method collected during
 * ANTLR4 parse-tree traversal.
 *
 * @param fqcn       fully qualified class name ({@code Namespace.ClassName})
 * @param methodName simple method name
 * @param attributes all attribute sections applied to the method
 * @param beginLine  1-based line of the first token of the method
 *                   (its first attribute section, or the return type)
 * @param endLine    1-based line of the closing brace (or semicolon)
 */
public record MethodInfo(
        String fqcn,
        String methodName,
        List<AttributeInfo> attributes,
        int beginLine,
        int endLine) {
}
