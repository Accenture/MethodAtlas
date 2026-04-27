package org.egothor.methodatlas;

import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;

/**
 * Consumer that receives a single discovered test method record.
 *
 * <p>
 * This functional interface decouples the scanning logic in
 * {@link MethodAtlasApp} from the output format. During a scan run, one
 * implementation is created up-front (either a CSV/plain-text lambda backed by
 * {@link OutputEmitter} or a buffering {@link SarifEmitter}), and every
 * discovered test method is forwarded to it via {@link #record}.
 * </p>
 *
 * @see OutputEmitter
 * @see SarifEmitter
 * @see MethodAtlasApp
 */
@FunctionalInterface
interface TestMethodSink {

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
     * @param tags        source-level JUnit {@code @Tag} values declared on the
     *                    method; never {@code null}
     * @param displayName text from an existing {@code @DisplayName} annotation on
     *                    the method; {@code null} when no {@code @DisplayName}
     *                    annotation is present; {@code ""} (empty string) when the
     *                    annotation is present but declares an empty value
     *                    ({@code @DisplayName("")}) — which is a malformed annotation
     *                    and may be treated as a quality finding by implementations
     * @param suggestion  AI-generated security classification for the method,
     *                    or {@code null} when AI enrichment is disabled or
     *                    unavailable for this class
     */
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion);
}
