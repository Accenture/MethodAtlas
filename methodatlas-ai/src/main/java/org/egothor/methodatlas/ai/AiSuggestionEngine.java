package org.egothor.methodatlas.ai;

import java.util.List;

/**
 * High-level AI orchestration contract for security classification of parsed
 * test classes.
 *
 * <p>
 * This interface defines the provider-agnostic entry point used by
 * {@code MethodAtlasApp} to request AI-generated security tagging
 * suggestions for a single parsed JUnit test class.
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
 * <li>optionally triaging deterministically-detected credential candidates,
 * either folded into the classification call
 * ({@link #suggestForClass(String, String, String, java.util.List, java.util.List)})
 * or as a dedicated request ({@link #triageSecrets}) &mdash; both added in
 * 4.1.0</li>
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

    /**
     * Requests AI classification <em>and</em> credential triage for a single class
     * in one provider call, so the class source is transmitted only once.
     *
     * <p>
     * When {@code secretCandidates} is empty this is equivalent to
     * {@link #suggestForClass(String, String, String, List)}. Otherwise the
     * returned {@link AiClassSuggestion} carries both the method classifications
     * and a {@link AiClassSuggestion#secrets()} list of verdicts for the supplied
     * candidates. The default implementation ignores the candidates and delegates
     * to the four-argument form, which suits engines that do not fold triage into
     * classification (the returned {@code secrets} is then {@code null}).
     * </p>
     *
     * @param fileStem         dot-separated path stem identifying the source file
     * @param fqcn             fully qualified class name of the parsed test class
     * @param classSource      complete source code of the class to analyze
     * @param targetMethods    deterministically extracted test methods to classify
     * @param secretCandidates credential candidates to triage in the same call;
     *                         never {@code null}; empty disables triage
     * @return normalized classification result, with secret verdicts when triage
     *         was folded in
     * @throws AiSuggestionException if analysis fails
     * @since 4.1.0
     */
    default AiClassSuggestion suggestForClass(String fileStem, String fqcn, String classSource,
            List<PromptBuilder.TargetMethod> targetMethods,
            List<PromptBuilder.CredentialCandidateRef> secretCandidates) throws AiSuggestionException {
        return suggestForClass(fileStem, fqcn, classSource, targetMethods);
    }

    /**
     * Registers an optional callback that the engine will notify after each
     * successful AI round-trip.
     *
     * <p>
     * The default implementation is a no-op so that engines that have no
     * meaningful response data to surface (for example the manual-consume
     * engine, which receives operator-saved files rather than live API
     * responses) silently ignore the registration. Engines that do back live
     * provider calls override this method to forward each call to the
     * registered listener.
     * </p>
     *
     * @param listener listener to notify after each successful AI round-trip,
     *                 or {@code null} to clear a previously registered listener
     */
    default void setResponseListener(AiResponseListener listener) {
        // no-op by default
    }

    /**
     * Triages deterministically-detected credential candidates for a single class.
     *
     * <p>
     * The candidate list is the closed, explicit input: implementations score
     * only the supplied candidates and never invent or omit any. The default
     * implementation returns an empty list, which suits engines that perform no
     * live triage (for example the manual-workflow consume engine); live engines
     * override it to issue a dedicated triage request.
     * </p>
     *
     * @param fqcn        fully qualified class name (or file identifier) for context;
     *                    never {@code null}
     * @param classSource complete source of the class being triaged; never {@code null}
     * @param candidates  detected candidates to score; never {@code null}
     * @return one {@link CredentialTriageVerdict} per scored candidate; never {@code null};
     *         empty when triage is unavailable
     * @throws AiSuggestionException if a live triage request fails
     * @since 4.1.0
     */
    default List<CredentialTriageVerdict> triageSecrets(String fqcn, String classSource,
            List<PromptBuilder.CredentialCandidateRef> candidates) throws AiSuggestionException {
        return List.of();
    }
}