package org.egothor.methodatlas.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One source file presented to a {@link CredentialDetector} for scanning.
 *
 * @param filePath   absolute path of the file; never {@code null}
 * @param fqcn       best-effort fully qualified class name, or {@code null} for a
 *                   file that is not a discovered test class (e.g. production
 *                   source matched by a wider mask)
 * @param source     full text of the file; never {@code null}
 * @param languageId short language identifier derived from the file extension
 *                   (e.g. {@code "java"}), or {@code null} when unknown
 * @since 4.1.0
 */
public record CredentialScanUnit(Path filePath, String fqcn, String source, String languageId) {

    /**
     * Validates that the required fields are present.
     *
     * @throws NullPointerException if {@code filePath} or {@code source} is {@code null}
     */
    public CredentialScanUnit {
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(source, "source");
    }
}
