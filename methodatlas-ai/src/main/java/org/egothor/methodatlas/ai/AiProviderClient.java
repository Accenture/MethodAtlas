package org.egothor.methodatlas.ai;

import java.util.List;

/**
 * Provider-specific client abstraction used to communicate with external AI
 * inference services. Sealed so that the orchestration layer can pattern-match
 * exhaustively across the four supported providers, and so that adding a new
 * provider is a deliberate two-step change (new permitted type plus factory
 * entry).
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
public sealed interface AiProviderClient
        permits OllamaClient, OpenAiCompatibleClient, AnthropicClient, AzureOpenAiClient {

    /**
     * Normalises a raw provider suggestion into the application's internal
     * result invariants.
     *
     * <p>
     * Provider response shapes differ, but the post-deserialisation cleanup is
     * identical across every provider: collection-valued fields are never
     * {@code null}, and malformed method entries (missing or blank
     * {@code methodName}) are filtered out. Hosting this logic on the sealed
     * interface guarantees the four providers cannot drift from each other.
     * </p>
     *
     * @param input raw suggestion returned by a provider; must not be
     *              {@code null}
     * @return normalised suggestion with non-null collections and
     *         well-formed method entries
     */
    static AiClassSuggestion normalize(AiClassSuggestion input) {
        List<AiMethodSuggestion> methods = input.methods() == null ? List.of() : input.methods();
        List<String> classTags = input.classTags() == null ? List.of() : input.classTags();

        List<AiMethodSuggestion> normalizedMethods = methods.stream()
                .filter(method -> method != null && method.methodName() != null && !method.methodName().isBlank())
                .map(method -> new AiMethodSuggestion(method.methodName(), method.securityRelevant(),
                        method.displayName(), method.tags() == null ? List.of() : method.tags(), method.reason(),
                        method.confidence(), method.interactionScore()))
                .toList();

        return new AiClassSuggestion(input.className(), input.classSecurityRelevant(), classTags, input.classReason(),
                normalizedMethods);
    }

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
     * The caller renders the complete user prompt — class source, security
     * taxonomy, and the deterministically discovered target methods — through
     * {@link PromptBuilder#build}. The implementation submits that prompt
     * verbatim to the underlying AI provider, wrapping it only in the
     * provider's native request envelope (system message, model identifier,
     * and sampling parameters), and analyzes the structured response.
     * </p>
     *
     * <p>
     * Centralising prompt assembly in the caller guarantees that every
     * provider sends an identical prompt and that observers (such as the
     * evidence-pack archive) record the exact text submitted rather than a
     * reconstruction of it.
     * </p>
     *
     * <p>
     * The response is normalized into an {@link AiClassSuggestion} instance
     * containing both class-level metadata and a list of {@link AiMethodSuggestion}
     * objects describing individual test methods.
     * </p>
     *
     * @param fqcn   fully qualified name of the analyzed class; used only to
     *               produce diagnostic messages
     * @param prompt fully rendered user prompt produced by
     *               {@link PromptBuilder#build}; must not be {@code null}
     * @return normalized AI classification result
     *
     * @throws AiSuggestionException if the request fails due to provider errors,
     *                               malformed responses, or communication failures
     *
     * @see AiClassSuggestion
     * @see AiMethodSuggestion
     * @see PromptBuilder#build(String, String, String, List, boolean)
     */
    AiClassSuggestion suggestForClass(String fqcn, String prompt) throws AiSuggestionException;
}