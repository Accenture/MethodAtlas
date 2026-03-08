package org.egothor.methodatlas.ai;

/**
 * Provider-specific client abstraction used to communicate with external AI
 * inference services.
 *
 * <p>
 * Implementations of this interface encapsulate the protocol and request
 * formatting required to interact with a particular AI provider such as OpenAI,
 * Ollama, Anthropic, or OpenRouter. The interface isolates the rest of the
 * application from provider-specific details including authentication, endpoint
 * layout, and response normalization.
 * </p>
 *
 * <p>
 * Instances are typically created by the AI integration layer during
 * initialization of the {@link AiSuggestionEngine}. Each client is responsible
 * for transforming a class-level analysis request into the provider’s native
 * API format and mapping the response back into the internal
 * {@link AiClassSuggestion} representation used by the application.
 * </p>
 *
 * <h2>Provider Responsibilities</h2>
 *
 * <ul>
 * <li>constructing provider-specific HTTP requests</li>
 * <li>handling authentication and API keys</li>
 * <li>sending inference requests</li>
 * <li>parsing and validating AI responses</li>
 * <li>normalizing results into {@link AiClassSuggestion}</li>
 * </ul>
 *
 * <p>
 * Implementations are expected to be stateless and thread-safe unless
 * explicitly documented otherwise.
 * </p>
 *
 * @see AiSuggestionEngine
 * @see AiClassSuggestion
 * @see AiProvider
 */
public interface AiProviderClient {
    /**
     * Determines whether the provider is reachable and usable in the current
     * runtime environment.
     *
     * <p>
     * Implementations typically perform a lightweight availability check such as
     * probing the provider's base endpoint or verifying that required configuration
     * (for example, API keys or local services) is present.
     * </p>
     *
     * <p>
     * This method is primarily used when {@link AiProvider#AUTO} selection is
     * enabled so the system can choose the first available provider.
     * </p>
     *
     * @return {@code true} if the provider appears available and ready to accept
     *         inference requests; {@code false} otherwise
     */
    boolean isAvailable();

    /**
     * Requests AI-based security classification for a parsed test class.
     *
     * <p>
     * The implementation submits the provided class source code together with the
     * taxonomy specification to the underlying AI provider. The provider analyzes
     * the class and produces structured classification results for the class itself
     * and for each test method contained within the class.
     * </p>
     *
     * <p>
     * The response is normalized into an {@link AiClassSuggestion} instance
     * containing both class-level metadata and a list of {@link AiMethodSuggestion}
     * objects describing individual test methods.
     * </p>
     *
     * @param fqcn         fully qualified name of the analyzed class
     * @param classSource  complete source code of the class being analyzed
     * @param taxonomyText security taxonomy definition guiding the AI
     *                     classification
     * @return normalized AI classification result
     *
     * @throws AiSuggestionException if the request fails due to provider errors,
     *                               malformed responses, or communication failures
     *
     * @see AiClassSuggestion
     * @see AiMethodSuggestion
     */
    AiClassSuggestion suggestForClass(String fqcn, String classSource, String taxonomyText)
            throws AiSuggestionException;
}