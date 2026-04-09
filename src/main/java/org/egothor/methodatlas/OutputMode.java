package org.egothor.methodatlas;

/**
 * Output formats supported by the MethodAtlas application.
 *
 * <p>
 * The selected mode determines both the emitted header and the per-method
 * output representation.
 * </p>
 *
 * @see OutputEmitter
 */
enum OutputMode {

    /**
     * Emits output in comma-separated value format.
     *
     * <p>
     * Fields are escaped according to RFC 4180 rules implemented by
     * {@link OutputEmitter}.
     * </p>
     */
    CSV,

    /**
     * Emits output in a human-readable plain text format.
     */
    PLAIN,

    /**
     * Emits output in SARIF 2.1.0 JSON format.
     *
     * <p>
     * Results are buffered in memory and serialized to a single JSON document
     * after all source files have been scanned. Each test method becomes one
     * SARIF result; security-relevant methods receive level {@code note}, all
     * others receive level {@code none}.
     * </p>
     *
     * @see <a href="https://docs.oasis-open.org/sarif/sarif/v2.1.0/">SARIF 2.1.0 specification</a>
     */
    SARIF
}
