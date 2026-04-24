package org.egothor.methodatlas.ai;

/**
 * Factory responsible for creating provider-specific AI client implementations.
 *
 * <p>
 * This class centralizes the logic for selecting and constructing concrete
 * {@link AiProviderClient} implementations based on the configuration provided
 * through {@link AiOptions}. It abstracts provider instantiation from the rest
 * of the application so that higher-level components interact only with the
 * {@link AiProviderClient} interface.
 * </p>
 *
 * <h2>Provider Resolution</h2>
 *
 * <p>
 * When an explicit provider is configured in {@link AiOptions#provider()}, the
 * factory constructs the corresponding client implementation. When
 * {@link AiProvider#AUTO} is selected, the factory attempts to determine a
 * suitable provider automatically using the following strategy:
 * </p>
 *
 * <ol>
 * <li>Attempt to use a locally running {@link OllamaClient}.</li>
 * <li>If Ollama is not reachable and an API key is configured, fall back to an
 * OpenAI-compatible provider.</li>
 * <li>If no provider can be resolved, an {@link AiSuggestionException} is
 * thrown.</li>
 * </ol>
 *
 * <h2>Azure OpenAI</h2>
 *
 * <p>
 * {@link AiProvider#AZURE_OPENAI} constructs an {@link AzureOpenAiClient}.
 * This provider is never selected automatically; it must be configured
 * explicitly. The {@link AiOptions#baseUrl()} must point to the Azure OpenAI
 * resource endpoint and {@link AiOptions#modelName()} must match the
 * deployment name as shown in the Azure portal.
 * </p>
 *
 * <p>
 * The factory ensures that returned clients are usable by verifying provider
 * availability when required.
 * </p>
 *
 * <p>
 * This class is intentionally non-instantiable and exposes only static factory
 * methods.
 * </p>
 *
 * @see AiProviderClient
 * @see AiProvider
 * @see AiOptions
 */
public final class AiProviderFactory {
    /**
     * Prevents instantiation of this utility class.
     */
    private AiProviderFactory() {
    }

    /**
     * Creates a provider-specific {@link AiProviderClient} based on the supplied
     * configuration.
     *
     * <p>
     * The selected provider determines which concrete implementation is
     * instantiated and how availability checks are performed. When
     * {@link AiProvider#AUTO} is configured, the method delegates provider
     * selection to {@link #auto(AiOptions)}.
     * </p>
     *
     * @param options AI configuration describing provider, model, endpoint,
     *                authentication, and runtime limits
     * @return initialized provider client ready to perform inference requests
     *
     * @throws AiSuggestionException if the provider cannot be initialized, required
     *                               authentication is missing, or no suitable
     *                               provider can be resolved
     */
    public static AiProviderClient create(AiOptions options) throws AiSuggestionException {
        return switch (options.provider()) {
            case OLLAMA -> new OllamaClient(options);
            case OPENAI -> requireAvailable(new OpenAiCompatibleClient(options), "OpenAI API key missing");
            case OPENROUTER -> requireAvailable(new OpenAiCompatibleClient(options), "OpenRouter API key missing");
            case ANTHROPIC -> requireAvailable(new AnthropicClient(options), "Anthropic API key missing");
            case AZURE_OPENAI -> requireAvailable(new AzureOpenAiClient(options), "Azure OpenAI API key missing");
            case AUTO -> auto(options);
        };
    }

    /**
     * Performs automatic provider discovery when {@link AiProvider#AUTO} is
     * selected.
     *
     * <p>
     * The discovery process prioritizes locally available inference services to
     * enable operation without external dependencies whenever possible.
     * </p>
     *
     * <p>
     * The current discovery strategy is:
     * </p>
     * <ol>
     * <li>Attempt to connect to a local {@link OllamaClient}.</li>
     * <li>If Ollama is not available but an API key is configured, create an
     * {@link OpenAiCompatibleClient}.</li>
     * <li>If neither provider can be used, throw an exception.</li>
     * </ol>
     *
     * @param options AI configuration used to construct candidate providers
     * @return resolved provider client
     *
     * @throws AiSuggestionException if no suitable provider can be discovered
     */
    private static AiProviderClient auto(AiOptions options) throws AiSuggestionException {
        AiOptions ollamaOptions = AiOptions.builder()
                .enabled(options.enabled())
                .provider(AiProvider.OLLAMA)
                .modelName(options.modelName())
                .baseUrl(options.baseUrl())
                .apiKey(options.apiKey())
                .apiKeyEnv(options.apiKeyEnv())
                .taxonomyFile(options.taxonomyFile())
                .taxonomyMode(options.taxonomyMode())
                .maxClassChars(options.maxClassChars())
                .timeout(options.timeout())
                .maxRetries(options.maxRetries())
                .build();

        OllamaClient ollamaClient = new OllamaClient(ollamaOptions);
        if (ollamaClient.isAvailable()) {
            return ollamaClient;
        }

        String apiKey = options.resolvedApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            return new OpenAiCompatibleClient(options);
        }

        throw new AiSuggestionException(
                "No AI provider available. Ollama is not reachable and no API key is configured.");
    }

    /**
     * Ensures that a provider client is available before returning it.
     *
     * <p>
     * This helper method invokes {@link AiProviderClient#isAvailable()} and throws
     * an {@link AiSuggestionException} if the provider cannot be used. It is
     * primarily used when constructing clients that require external services or
     * authentication to function correctly.
     * </p>
     *
     * @param client  provider client to verify
     * @param message error message used if the provider is unavailable
     * @return the supplied client if it is available
     *
     * @throws AiSuggestionException if the provider reports that it is not
     *                               available
     */
    private static AiProviderClient requireAvailable(AiProviderClient client, String message)
            throws AiSuggestionException {
        if (!client.isAvailable()) {
            throw new AiSuggestionException(message);
        }
        return client;
    }
}