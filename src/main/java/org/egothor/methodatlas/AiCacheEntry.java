package org.egothor.methodatlas;

import org.egothor.methodatlas.ai.AiClassSuggestion;

/**
 * One entry of the unified AI result cache: the complete AI answer for a single
 * test class, keyed by the class content hash and tagged with the prompt-catalogue
 * signature that produced it.
 *
 * <p>
 * The {@code suggestion} carries <em>both</em> the method classifications
 * ({@link AiClassSuggestion#methods()}) and any credential-triage verdicts
 * ({@link AiClassSuggestion#secrets()}), so a single cached entry can answer a later
 * classification-only run, a later credential run, or a combined run — without
 * re-querying the model — as long as {@link #promptSignature()} matches the current
 * run's {@link org.egothor.methodatlas.ai.PromptTemplateSet#signature()}.
 * </p>
 *
 * <p>
 * A {@code null} {@code promptSignature} denotes a legacy entry (loaded from a
 * pre-unified CSV cache): its classifications may still be reused by content hash,
 * but it carries no credential verdicts and cannot satisfy a credential query.
 * </p>
 *
 * @param contentHash     SHA-256 fingerprint of the class source; never {@code null}
 * @param promptSignature catalogue signature that produced this answer, or
 *                        {@code null} for a legacy CSV-sourced entry
 * @param suggestion      the cached AI answer (classifications and, when present,
 *                        credential verdicts); never {@code null}
 * @since 4.1.0
 */
public record AiCacheEntry(String contentHash, String promptSignature, AiClassSuggestion suggestion) {
}
