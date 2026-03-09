package org.egothor.methodatlas.ai;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@link AiProviderClient} implementation for AI providers that expose an
 * OpenAI-compatible chat completion API.
 *
 * <p>
 * This client supports providers that implement the OpenAI-style
 * {@code /v1/chat/completions} endpoint. The same implementation is used for:
 * </p>
 *
 * <ul>
 * <li>{@link AiProvider#OPENAI}</li>
 * <li>{@link AiProvider#OPENROUTER}</li>
 * </ul>
 *
 * <p>
 * The client constructs a chat-style prompt consisting of a system message
 * defining the classification rules and a user message containing the test
 * class source together with the taxonomy definition. The model response is
 * expected to contain a JSON object describing the security classification.
 * </p>
 *
 * <h2>Operational Responsibilities</h2>
 *
 * <ul>
 * <li>constructing OpenAI-compatible chat completion requests</li>
 * <li>injecting the taxonomy-driven classification prompt</li>
 * <li>performing authenticated HTTP requests</li>
 * <li>extracting JSON content from the model response</li>
 * <li>normalizing the result into {@link AiClassSuggestion}</li>
 * </ul>
 *
 * <p>
 * The implementation is provider-neutral for APIs that follow the OpenAI
 * protocol, which allows reuse across multiple compatible services such as
 * OpenRouter.
 * </p>
 *
 * <p>
 * Instances are typically created through
 * {@link AiProviderFactory#create(AiOptions)}.
 * </p>
 *
 * @see AiProvider
 * @see AiProviderClient
 * @see AiProviderFactory
 */
public final class OpenAiCompatibleClient implements AiProviderClient {
    /**
     * System prompt instructing the model to operate strictly as a classification
     * engine and to return machine-readable JSON output.
     *
     * <p>
     * The prompt intentionally forbids explanatory text and markdown formatting to
     * ensure that the returned content can be parsed reliably by the application.
     * </p>
     */
    private static final String SYSTEM_PROMPT = """
            You are a precise software security classification engine.
            You classify JUnit 5 tests and return strict JSON only.
            Never include markdown fences, explanations, or extra text.
            """;

    private final AiOptions options;
    private final HttpSupport httpSupport;

    /**
     * Creates a new client for an OpenAI-compatible provider.
     *
     * <p>
     * The supplied configuration determines the provider endpoint, model name,
     * authentication method, request timeout, and other runtime parameters.
     * </p>
     *
     * @param options AI runtime configuration
     */
    public OpenAiCompatibleClient(AiOptions options) {
        this.options = options;
        this.httpSupport = new HttpSupport(options.timeout());
    }

    /**
     * Determines whether the configured provider can be used in the current runtime
     * environment.
     *
     * <p>
     * For OpenAI-compatible providers, availability is determined by the presence
     * of a usable API key resolved through {@link AiOptions#resolvedApiKey()}.
     * </p>
     *
     * @return {@code true} if a usable API key is available
     */
    @Override
    public boolean isAvailable() {
        return options.resolvedApiKey() != null && !options.resolvedApiKey().isBlank();
    }

    /**
     * Submits a classification request to an OpenAI-compatible chat completion API.
     *
     * <p>
     * The request payload includes:
     * </p>
     *
     * <ul>
     * <li>the configured model identifier</li>
     * <li>a system prompt defining classification rules</li>
     * <li>a user prompt containing the test class source and taxonomy</li>
     * <li>a deterministic temperature setting</li>
     * </ul>
     *
     * <p>
     * When the selected provider is {@link AiProvider#OPENROUTER}, additional HTTP
     * headers are included to identify the calling application.
     * </p>
     *
     * <p>
     * The response is expected to contain a JSON object in the message content
     * field. The JSON text is extracted and deserialized into an
     * {@link AiClassSuggestion}.
     * </p>
     *
     * @param fqcn         fully qualified class name being analyzed
     * @param classSource  complete source code of the class
     * @param taxonomyText taxonomy definition guiding classification
     *
     * @return normalized classification result
     *
     * @throws AiSuggestionException if the provider request fails, the model
     *                               response is invalid, or JSON deserialization
     *                               fails
     */
    @Override
    public AiClassSuggestion suggestForClass(String fqcn, String classSource, String taxonomyText)
            throws AiSuggestionException {
        try {
            String prompt = PromptBuilder.build(fqcn, classSource, taxonomyText);

            ChatRequest payload = new ChatRequest(options.modelName(),
                    List.of(new Message("system", SYSTEM_PROMPT), new Message("user", prompt)), 0.0);

            String requestBody = httpSupport.objectMapper().writeValueAsString(payload);

            URI uri = URI.create(options.baseUrl() + "/v1/chat/completions");
            HttpRequest.Builder requestBuilder = httpSupport.jsonPost(uri, requestBody, options.timeout())
                    .header("Authorization", "Bearer " + options.resolvedApiKey());

            if (options.provider() == AiProvider.OPENROUTER) {
                requestBuilder.header("HTTP-Referer", "https://methodatlas.local");
                requestBuilder.header("X-Title", "MethodAtlas");
            }

            String responseBody = httpSupport.postJson(requestBuilder.build());
            ChatResponse response = httpSupport.objectMapper().readValue(responseBody, ChatResponse.class);

            if (response.choices == null || response.choices.isEmpty()) {
                throw new AiSuggestionException("No choices returned by model");
            }

            String content = response.choices.get(0).message.content;
            String json = JsonText.extractFirstJsonObject(content);
            AiClassSuggestion suggestion = httpSupport.objectMapper().readValue(json, AiClassSuggestion.class);
            return normalize(suggestion);

        } catch (Exception e) { // NOPMD
            throw new AiSuggestionException("OpenAI-compatible suggestion failed for " + fqcn, e);
        }
    }

    /**
     * Normalizes provider results to ensure structural invariants expected by the
     * application.
     *
     * <p>
     * The method replaces {@code null} collections with empty lists and removes
     * malformed method entries that do not contain a valid method name.
     * </p>
     *
     * @param input raw suggestion returned by the provider
     * @return normalized suggestion instance
     */
    private static AiClassSuggestion normalize(AiClassSuggestion input) {
        List<AiMethodSuggestion> methods = input.methods() == null ? List.of() : input.methods();
        List<String> classTags = input.classTags() == null ? List.of() : input.classTags();

        List<AiMethodSuggestion> normalizedMethods = methods.stream()
                .filter(method -> method != null && method.methodName() != null && !method.methodName().isBlank())
                .map(method -> new AiMethodSuggestion(method.methodName(), method.securityRelevant(),
                        method.displayName(), method.tags() == null ? List.of() : method.tags(), method.reason()))
                .toList();

        return new AiClassSuggestion(input.className(), input.classSecurityRelevant(), classTags, input.classReason(),
                normalizedMethods);
    }

    /**
     * Request payload for an OpenAI-compatible chat completion request.
     *
     * @param model       model identifier used for inference
     * @param messages    ordered chat messages sent to the model
     * @param temperature sampling temperature controlling response variability
     */
    private record ChatRequest(String model, List<Message> messages, @JsonProperty("temperature") Double temperature) {
    }

    /**
     * Chat message included in the request payload.
     *
     * @param role    logical role of the message sender, such as {@code system} or
     *                {@code user}
     * @param content textual message content
     */
    private record Message(String role, String content) {
    }

    /**
     * Partial response model returned by the chat completion API.
     *
     * <p>
     * Only fields required for extracting the model response are mapped. Unknown
     * properties are ignored to preserve compatibility with provider API changes.
     * </p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ChatResponse {
        public List<Choice> choices;
    }

    /**
     * Individual completion choice returned by the provider.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Choice {
        public ResponseMessage message;
    }

    /**
     * Message payload returned inside a completion choice.
     *
     * <p>
     * The {@code content} field is expected to contain the JSON classification
     * result generated by the model.
     * </p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ResponseMessage {
        public String content;
    }
}