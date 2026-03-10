package org.egothor.methodatlas.ai;

import java.util.List;

/**
 * High-level AI orchestration contract for security classification of parsed
 * test classes.
 *
 * <p>
 * This interface defines the provider-agnostic entry point used by
 * {@link org.egothor.methodatlas.MethodAtlasApp} to request AI-generated
 * security tagging suggestions for a single parsed JUnit test class.
 * Implementations coordinate taxonomy selection, provider resolution, request
 * submission, response normalization, and conversion into the application's
 * internal result model.
 * </p>
 *
 * <h2>Responsibilities</h2>
 *
 * <ul>
 * <li>accepting a fully qualified class name and corresponding class
 * source</li>
 * <li>submitting the class for AI-based security analysis</li>
 * <li>normalizing provider-specific responses into
 * {@link AiClassSuggestion}</li>
 * <li>surfacing failures through {@link AiSuggestionException}</li>
 * </ul>
 *
 * <p>
 * The interface intentionally hides provider-specific protocol details so that
 * the rest of the application can depend on a stable abstraction independent of
 * the selected AI backend.
 * </p>
 *
 * @see AiClassSuggestion
 * @see AiProviderClient
 * @see org.egothor.methodatlas.MethodAtlasApp
 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface AiSuggestionEngine {
    /**
     * Requests AI-generated security classification for a single parsed test class.
     *
     * <p>
     * The supplied source code is analyzed in the context of the configured
     * taxonomy and AI provider. The returned result may contain both class-level
     * and method-level suggestions, including security relevance, display name
     * proposals, taxonomy tags, and optional explanatory rationale.
     * </p>
     *
     * <p>
     * The method expects the complete source of the class being analyzed, rather
     * than a single method fragment, so that the AI engine can evaluate test intent
     * using full class context.
     * </p>
     *
     * @param fqcn          fully qualified class name of the parsed test class
     * @param classSource   complete source code of the class to analyze
     * @param targetMethods deterministically extracted JUnit test methods that must
     *                      be classified
     * @return normalized AI classification result for the class and its methods
     *
     * @throws AiSuggestionException if analysis fails due to provider communication
     *                               errors, invalid responses, provider
     *                               unavailability, or normalization failures
     *
     * @see AiClassSuggestion
     * @see AiMethodSuggestion
     */
    AiClassSuggestion suggestForClass(String fqcn, String classSource, List<PromptBuilder.TargetMethod> targetMethods)
            throws AiSuggestionException;
}