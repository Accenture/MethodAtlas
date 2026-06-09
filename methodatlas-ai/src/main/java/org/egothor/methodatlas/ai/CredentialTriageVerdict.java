package org.egothor.methodatlas.ai;

/**
 * LLM assessment of one deterministically-detected credential candidate.
 *
 * <p>
 * Produced by the AI triage layer and correlated back to the exact candidate
 * the regex engine found via {@link #candidateIndex()}. The deterministic
 * detector remains the source of truth for <em>which</em> spans exist; this
 * record only adds the model's interpretation of them.
 * </p>
 *
 * @param candidateIndex   zero-based index of the candidate in the request list;
 *                         ties the verdict back to the exact detected span
 * @param credibilityScore likelihood the candidate is a genuine, live credential,
 *                         in {@code [0.0, 1.0]} ({@code 1.0} = almost certainly
 *                         real, {@code 0.0} = almost certainly a placeholder or
 *                         false positive)
 * @param endpoint         endpoint or system the credential authenticates against,
 *                         or {@code null} when the model could not attribute one
 * @param rationale        short explanation of the score; may be {@code null}
 * @since 4.1.0
 */
public record CredentialTriageVerdict(int candidateIndex, double credibilityScore,
        String endpoint, String rationale) {
}
