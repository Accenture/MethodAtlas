package org.egothor.methodatlas.api;

import java.nio.file.Path;
import java.util.List;

/**
 * A single test method discovered by a {@link TestDiscovery} implementation.
 *
 * <p>
 * Carries the structural metadata extracted from the source file alongside
 * a {@link SourceContent} provider that gives the orchestration layer
 * (AI analysis, content hashing) on-demand access to the enclosing class
 * source without the scanner having to pre-read files it may never need.
 * All methods belonging to the same class share a single {@link SourceContent}
 * instance.
 * </p>
 *
 * @param fqcn          fully qualified name of the class that declares the method
 * @param method        simple method name
 * @param beginLine     one-based line number of the first line of the method
 *                      declaration; {@code 0} when position information is
 *                      unavailable
 * @param endLine       one-based line number of the last line of the method
 *                      declaration; {@code 0} when position information is
 *                      unavailable
 * @param loc           inclusive line count of the method declaration
 * @param tags          source-level test-framework tag values declared on the
 *                      method; never {@code null}
 * @param displayName   text from an existing display-name annotation on the
 *                      method; {@code null} when no such annotation is present;
 *                      {@code ""} when the annotation is present but has an
 *                      empty value
 * @param filePath      absolute path of the source file that contains this method
 * @param fileStem      dot-separated identifier derived from the file path
 *                      relative to the scan root (e.g.
 *                      {@code com.acme.AuthTest}); used to name work files
 *                      in the manual AI workflow
 * @param sourceContent lazy provider of the enclosing class source text;
 *                      shared by all methods of the same class
 */
public record DiscoveredMethod(
        String fqcn,
        String method,
        int beginLine,
        int endLine,
        int loc,
        List<String> tags,
        String displayName,
        Path filePath,
        String fileStem,
        SourceContent sourceContent) {
}
