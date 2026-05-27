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
     * Constructs an {@code AiSuggestionEngine} configured for the active AI
     * provider. Callers receive an instance through this static factory
     * rather than naming the implementation class directly, which keeps the
     * concrete type out of caller imports and makes the impl substitutable
     * (for example, by a stub in unit tests or by a future alternative
     * implementation).
     *
     * <p>
     * The returned engine is stateless beyond its configuration and is safe
     * to share across threads.
     * </p>
     *
     * @param options AI runtime configuration; must not be {@code null}
     * @return a configured engine ready to handle
     *         {@link #suggestForClass(String, String, String, java.util.List)}
     *         calls
     * @throws AiSuggestionException if the engine cannot be initialised for
     *                               the supplied options
     */
    static AiSuggestionEngine create(AiOptions options) throws AiSuggestionException {
        return new AiSuggestionEngineImpl(options);
    }

    /**
     * Constructs an {@code AiSuggestionEngine} that notifies
     * {@code rateLimitListener} before each rate-limit pause. The listener is
     * the GUI's hook into long-running calls and is supplied by the Swing
     * worker that orchestrates analysis.
     *
     * @param options           AI runtime configuration; must not be {@code null}
     * @param rateLimitListener callback invoked before each HTTP&nbsp;429
     *                          pause; must not be {@code null}
     * @return a configured engine
     * @throws AiSuggestionException if the engine cannot be initialised
     * @see RateLimitListener
     */
    static AiSuggestionEngine create(AiOptions options, RateLimitListener rateLimitListener)
            throws AiSuggestionException {
        return new AiSuggestionEngineImpl(options, rateLimitListener);
    }

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
     * <p>
     * The {@code fileStem} parameter is a dot-separated identifier derived from the
     * source file's path relative to the scan root (e.g.
     * {@code module-a.src.test.java.com.acme.FooTest}). Automated provider
     * implementations ignore it; the {@link ManualConsumeEngine} uses it to locate
     * the operator-saved response file ({@code <fileStem>.response.txt}).
     * </p>
     *
     * @param fileStem      dot-separated path stem identifying the source file;
     *                      used by file-based engines to locate response files
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
     * @see ManualConsumeEngine
     */
    AiClassSuggestion suggestForClass(String fileStem, String fqcn, String classSource,
            List<PromptBuilder.TargetMethod> targetMethods) throws AiSuggestionException;
}