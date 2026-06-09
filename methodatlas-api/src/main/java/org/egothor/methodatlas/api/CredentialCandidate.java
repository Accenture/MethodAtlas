package org.egothor.methodatlas.api;

import java.util.Objects;

/**
 * A single credential candidate located by a {@link CredentialDetector}.
 *
 * <p>
 * The candidate is purely a deterministic detection result: it carries no
 * credibility score, endpoint attribution, or enclosing method. Those are
 * downstream enrichments added by the orchestration and AI layers. The
 * {@code matchedValue} is the raw matched text and is held in memory only;
 * output layers mask it unless the operator opts into full values.
 * </p>
 *
 * @param detectorId  identifier of the {@link CredentialDetector} that produced this
 *                    candidate; never {@code null}
 * @param ruleId      identifier of the catalog rule that matched; never {@code null}
 * @param category    coarse classification of the candidate; never {@code null}
 * @param beginLine   one-based line of the first matched character
 * @param beginColumn one-based column of the first matched character
 * @param endLine     one-based line of the last matched character
 * @param endColumn   one-based column after the last matched character
 * @param matchedValue raw matched text; never {@code null}
 * @since 4.1.0
 */
public record CredentialCandidate(
        String detectorId, String ruleId, CredentialCategory category,
        int beginLine, int beginColumn, int endLine, int endColumn,
        String matchedValue) {

    /**
     * Validates that the reference fields are present.
     *
     * @throws NullPointerException if any reference field is {@code null}
     */
    public CredentialCandidate {
        Objects.requireNonNull(detectorId, "detectorId");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(matchedValue, "matchedValue");
    }
}
