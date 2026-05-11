package org.egothor.methodatlas;

/**
 * Output formats supported by the MethodAtlas application.
 *
 * <p>
 * The selected mode determines both the emitted header and the per-method
 * output representation.
 * </p>
 *
 * @see org.egothor.methodatlas.emit.OutputEmitter
 */
public enum OutputMode {

    /**
     * Emits output in comma-separated value format.
     *
     * <p>
     * Fields are escaped according to RFC 4180 rules implemented by
     * {@link org.egothor.methodatlas.emit.OutputEmitter}.
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
     * after all source files have been scanned. Security-relevant methods receive
     * SARIF level {@code note} and a {@code security-severity} score derived from
     * the AI taxonomy tag; rule objects carry a {@code properties.tags} array for
     * the GitHub Code Scanning tag filter.
     * </p>
     *
     * <p>
     * <b>Security-only by default:</b> SARIF output is intended for consumption
     * by GitHub Code Scanning and equivalent security tooling. These consumers
     * expect actionable security findings, not an exhaustive inventory of every
     * test method. Selecting this mode therefore applies the security-only filter
     * automatically: only methods classified as security-relevant are emitted.
     * Pass {@code -include-non-security} on the command line (or set
     * {@code includeNonSecurity: true} in the YAML configuration file) to
     * include all methods in the SARIF document.
     * </p>
     *
     * @see <a href="https://docs.oasis-open.org/sarif/sarif/v2.1.0/">SARIF 2.1.0 specification</a>
     */
    SARIF,

    /**
     * Emits GitHub Actions workflow commands for inline PR annotations.
     *
     * <p>
     * Only security-relevant methods produce output. Each method becomes one
     * {@code ::notice} or {@code ::warning} workflow command that GitHub Actions
     * intercepts and displays as an inline annotation on the PR diff. The level
     * is {@code warning} when {@code ai_interaction_score >= 0.8} (potential
     * placebo test) and {@code notice} otherwise.
     * </p>
     *
     * <p>
     * Non-security methods produce no output. This mode does not require a
     * GitHub Advanced Security licence, unlike SARIF upload via
     * {@code upload-sarif}.
     * </p>
     *
     * @see org.egothor.methodatlas.emit.GitHubAnnotationsEmitter
     */
    GITHUB_ANNOTATIONS,

    /**
     * Emits output as a flat JSON array.
     *
     * <p>
     * Each element of the array is a JSON object containing the same fields as
     * the CSV output, with the following representation differences:
     * </p>
     * <ul>
     * <li>{@code tags} and {@code ai_tags} are JSON arrays rather than
     *     semicolon-separated strings</li>
     * <li>Numeric fields ({@code loc}, {@code ai_interaction_score},
     *     {@code ai_confidence}) are JSON numbers</li>
     * <li>{@code ai_security_relevant} is a JSON boolean</li>
     * <li>Optional columns ({@code source_root}, {@code content_hash},
     *     {@code ai_*}, {@code tag_ai_drift}) are omitted from each object
     *     when the corresponding flag is not enabled</li>
     * </ul>
     *
     * <p>
     * All records are buffered in memory and the complete array is written once
     * the scan finishes, matching the SARIF emitter's behaviour.
     * </p>
     *
     * @see org.egothor.methodatlas.emit.JsonEmitter
     */
    JSON
}
