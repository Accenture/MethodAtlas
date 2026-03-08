package org.egothor.methodatlas.ai;

import java.util.List;

/**
 * Immutable AI-generated security classification result for a single test
 * method.
 *
 * <p>
 * This record represents the normalized method-level output returned by an
 * {@link org.egothor.methodatlas.ai.AiSuggestionEngine} after analyzing the
 * source of a JUnit test class. Each instance describes the AI's interpretation
 * of the security relevance and taxonomy classification of one test method.
 * </p>
 *
 * <p>
 * The classification data contained in this record is typically produced by an
 * external AI provider and normalized by the application's AI integration layer
 * before being returned to the scanning logic. The resulting values are then
 * merged with source-derived metadata during output generation.
 * </p>
 *
 * <p>
 * The fields of this record correspond to the security analysis dimensions used
 * by the {@code MethodAtlasApp} enrichment pipeline:
 * </p>
 *
 * <ul>
 * <li>whether the test method validates a security property</li>
 * <li>a suggested {@code @DisplayName} describing the security intent</li>
 * <li>taxonomy-based security tags associated with the test</li>
 * <li>a short explanatory rationale describing the classification</li>
 * </ul>
 *
 * <p>
 * Instances of this record are typically stored in a
 * {@link org.egothor.methodatlas.ai.SuggestionLookup} and retrieved using the
 * method name as the lookup key when emitting enriched scan results.
 * </p>
 *
 * @param methodName       name of the analyzed test method as reported by the
 *                         AI
 * @param securityRelevant {@code true} if the AI classified the test as
 *                         validating a security property
 * @param displayName      suggested {@code @DisplayName} value describing the
 *                         security intent of the test; may be {@code null}
 * @param tags             taxonomy-based security tags suggested for the test
 *                         method; may be empty or {@code null} depending on
 *                         provider response
 * @param reason           explanatory rationale describing why the method was
 *                         classified as security-relevant or why specific tags
 *                         were assigned; may be {@code null}
 *
 * @see org.egothor.methodatlas.MethodAtlasApp
 * @see org.egothor.methodatlas.ai.AiSuggestionEngine
 * @see org.egothor.methodatlas.ai.SuggestionLookup
 */
public record AiMethodSuggestion(String methodName, boolean securityRelevant, String displayName, List<String> tags,
        String reason) {
}