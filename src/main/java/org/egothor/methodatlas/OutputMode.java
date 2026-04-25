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
     * @see GitHubAnnotationsEmitter
     */
    GITHUB_ANNOTATIONS
}
