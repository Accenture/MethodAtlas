package org.egothor.methodatlas.emit;

import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;

/**
 * Consumer that receives a single discovered test method record.
 *
 * <p>
 * This functional interface decouples the orchestration layer from the output
 * format. During a scan run, one implementation is created up-front and every
 * discovered test method is forwarded to it via {@link #record}.
 * </p>
 *
 * @see org.egothor.methodatlas.emit.OutputEmitter
 * @see org.egothor.methodatlas.emit.SarifEmitter
 */
@FunctionalInterface
public interface TestMethodSink {

    /**
     * Records a single test method.
     *
     * @param fqcn        fully qualified name of the class that declares the
     *                    method
     * @param method      simple method name
     * @param beginLine   one-based line number of the first line of the method
     *                    declaration; {@code 0} when the parser cannot determine
     *                    the location
     * @param loc         inclusive line count of the method declaration
     * @param contentHash lowercase-hex SHA-256 fingerprint of the enclosing
     *                    class source, or {@code null} when
     *                    {@code -content-hash} is not enabled
     * @param tags        source-level test-framework tag values declared on the
     *                    method; never {@code null}
     * @param displayName text from an existing display-name annotation on the
     *                    method; {@code null} when no such annotation is present;
     *                    {@code ""} when the annotation is present but has an
     *                    empty value — which is a malformed annotation and may
     *                    be treated as a quality finding by implementations
     * @param suggestion  AI-generated security classification for the method,
     *                    or {@code null} when AI enrichment is disabled or
     *                    unavailable for this class
     */
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion);

    /**
     * Records a single test method together with the real path of the source file
     * that declares it.
     *
     * <p>
     * Sinks that emit file locations (SARIF, GitHub annotations) override this to
     * use {@code sourcePath} instead of synthesising a Java-style path from the
     * FQCN, which is wrong for every non-Java language. The default implementation
     * ignores the path and delegates to
     * {@link #record(String, String, int, int, String, List, String, AiMethodSuggestion)}.
     * </p>
     *
     * @param fqcn        fully qualified name of the declaring class
     * @param method      simple method name
     * @param beginLine   one-based first line of the method, or {@code 0}
     * @param loc         inclusive line count of the method declaration
     * @param contentHash class content fingerprint, or {@code null}
     * @param tags        source-level tag values; never {@code null}
     * @param displayName existing display-name text, or {@code null}
     * @param suggestion  AI classification, or {@code null}
     * @param sourcePath  forward-slash path of the source file relative to the scan
     *                    root, or {@code null} when unknown
     */
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    default void recordWithSourcePath(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion, String sourcePath) {
        record(fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion);
    }
}
