package org.egothor.methodatlas.ai;

import java.util.List;

/**
 * Immutable AI-generated classification result for a single parsed test class.
 *
 * <p>
 * This record represents the structured output returned by an AI suggestion
 * engine after analyzing the source of one JUnit test class. It contains both
 * optional class-level security classification data and method-level
 * suggestions for individual test methods declared within the class.
 * </p>
 *
 * <p>
 * Class-level fields describe whether the class as a whole appears to be
 * security-relevant and, if so, which aggregate tags or rationale apply.
 * Method-level results are provided separately through {@link #methods()} and
 * are typically used by the calling code as the primary source for per-method
 * enrichment of emitted scan results.
 * </p>
 *
 * <p>
 * Instances of this record are commonly deserialized from provider-specific AI
 * responses after normalization into the application's internal result model.
 * </p>
 *
 * @param className             simple or fully qualified class name reported by
 *                              the AI; may be {@code null} if omitted by the
 *                              provider response
 * @param classSecurityRelevant whether the class as a whole is considered
 *                              security-relevant; may be {@code null} when the
 *                              AI does not provide a class-level decision
 * @param classTags             class-level security tags suggested by the AI;
 *                              may be empty or {@code null} depending on
 *                              response normalization
 * @param classReason           explanatory rationale for the class-level
 *                              classification; may be {@code null}
 * @param methods               method-level suggestions produced for individual
 *                              test methods; may be empty or {@code null}
 *                              depending on response normalization
 * @see AiMethodSuggestion
 * @see org.egothor.methodatlas.ai.AiSuggestionEngine
 */
public record AiClassSuggestion(String className, Boolean classSecurityRelevant, List<String> classTags,
        String classReason, List<AiMethodSuggestion> methods) {
}