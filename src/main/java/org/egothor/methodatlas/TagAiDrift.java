package org.egothor.methodatlas;

import java.util.List;
import java.util.Locale;

import org.egothor.methodatlas.ai.AiMethodSuggestion;

/**
 * Describes the agreement between a source-level {@code @Tag("security")} annotation
 * and the AI classification of the same test method.
 *
 * <p>Both the static annotation and the AI judgment are independent sources of truth.
 * When they disagree the discrepancy is called <em>drift</em>:</p>
 *
 * <ul>
 *   <li>{@link #TAG_ONLY} — the method carries {@code @Tag("security")} in source but
 *       the AI considers it non-security-relevant. The annotation may be stale,
 *       inaccurate, or applied to the wrong method. Tag-based CI gates and audit
 *       dashboards over-count security coverage.</li>
 *   <li>{@link #AI_ONLY} — the AI classifies the method as security-relevant but no
 *       {@code @Tag("security")} is present in source. Coverage dashboards and
 *       tag-based CI gates silently miss this test.</li>
 *   <li>{@link #NONE} — both sources agree; no action needed.</li>
 * </ul>
 *
 * <p>Drift detection requires an active AI classification. When no
 * {@link AiMethodSuggestion} is available (AI disabled, class too large, etc.)
 * {@link #compute} returns {@code null}.</p>
 *
 * @see MethodAtlasApp
 * @see OutputEmitter
 * @see SarifEmitter
 */
enum TagAiDrift {

    /** Both sources agree — either both say security-relevant or neither does. */
    NONE,

    /**
     * Source code carries {@code @Tag("security")} but the AI disagrees.
     * The annotation may be stale, inaccurate, or applied to the wrong method.
     */
    TAG_ONLY,

    /**
     * AI classifies the method as security-relevant but no {@code @Tag("security")}
     * is present in source. Coverage dashboards and tag-based CI gates will miss it.
     */
    AI_ONLY;

    private static final String SECURITY_TAG_VALUE = "security";

    /**
     * Computes the drift between source-level security tags and the AI classification.
     *
     * @param sourceTags JUnit {@code @Tag} values extracted from the method
     * @param suggestion AI classification for the method, or {@code null} when AI
     *                   is disabled or unavailable
     * @return computed drift value, or {@code null} when {@code suggestion} is
     *         {@code null} (drift cannot be determined without AI classification)
     */
    /* default */ static TagAiDrift compute(List<String> sourceTags, AiMethodSuggestion suggestion) {
        if (suggestion == null) {
            return null;
        }
        boolean hasTag = sourceTags.stream()
                .anyMatch(SECURITY_TAG_VALUE::equalsIgnoreCase);
        boolean aiSaysSecure = suggestion.securityRelevant();
        if (hasTag == aiSaysSecure) {
            return NONE;
        }
        return hasTag ? TAG_ONLY : AI_ONLY;
    }

    /**
     * Returns the lowercase hyphenated string representation used in CSV and SARIF output.
     *
     * @return {@code "none"}, {@code "tag-only"}, or {@code "ai-only"}
     */
    /* default */ String toValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
