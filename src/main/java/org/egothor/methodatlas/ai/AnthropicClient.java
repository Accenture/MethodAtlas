package org.egothor.methodatlas.ai;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@link AiProviderClient} implementation for the Anthropic API.
 *
 * <p>
 * This client submits classification requests to the Anthropic
 * <a href="https://docs.anthropic.com/">Claude API</a> and converts the
 * returned response into the internal {@link AiClassSuggestion} model used by
 * the MethodAtlas AI subsystem.
 * </p>
 *
 * <h2>Operational Responsibilities</h2>
 *
 * <ul>
 * <li>constructing Anthropic message API requests</li>
 * <li>injecting the taxonomy-driven classification prompt</li>
 * <li>performing authenticated HTTP calls to the Anthropic service</li>
 * <li>extracting the JSON result embedded in the model response</li>
 * <li>normalizing the result into {@link AiClassSuggestion}</li>
 * </ul>
 *
 * <p>
 * The client uses the {@code /v1/messages} endpoint and relies on the Claude
 * message format, where a system prompt defines classification rules and the
 * user message contains the class source together with the taxonomy
 * specification.
 * </p>
 *
 * <p>
 * Instances of this class are typically created by
 * {@link AiProviderFactory#create(AiOptions)}.
 * </p>
 *
 * <p>
 * This implementation is stateless apart from immutable configuration and is
 * therefore safe for reuse across multiple requests.
 * </p>
 *
 * @see AiProviderClient
 * @see AiSuggestionEngine
 * @see AiProviderFactory
 */
public final class AnthropicClient implements AiProviderClient {
    /**
     * System prompt used to instruct the model to return strictly formatted JSON
     * responses suitable for automated parsing.
     *
     * <p>
     * The prompt enforces deterministic output behavior and prevents the model from
     * returning explanations, markdown formatting, or conversational responses that
     * would break the JSON extraction pipeline.
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
     * Creates a new Anthropic client using the supplied runtime configuration.
     *
     * <p>
     * The configuration defines the model identifier, API endpoint, request
     * timeout, and authentication settings used when communicating with the
     * Anthropic service.
     * </p>
     *
     * @param options AI runtime configuration
     */
    public AnthropicClient(AiOptions options) {
        this.options = options;
        this.httpSupport = new HttpSupport(options.timeout());
    }

    /**
     * Determines whether the Anthropic provider can be used in the current runtime
     * environment.
     *
     * <p>
     * The provider is considered available when a non-empty API key can be resolved
     * from {@link AiOptions#resolvedApiKey()}.
     * </p>
     *
     * @return {@code true} if a usable API key is configured
     */
    @Override
    public boolean isAvailable() {
        String key = options.resolvedApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * Submits a classification request to the Anthropic API for the specified test
     * class.
     *
     * <p>
     * The method constructs a message-based request containing:
     * </p>
     *
     * <ul>
     * <li>a system prompt enforcing deterministic JSON output</li>
     * <li>a user prompt containing the class source and taxonomy definition</li>
     * </ul>
     *
     * <p>
     * The response is parsed to extract the first JSON object returned by the
     * model, which is then deserialized into an {@link AiClassSuggestion}.
     * </p>
     *
     * @param fqcn          fully qualified class name being analyzed
     * @param classSource   complete source code of the class
     * @param taxonomyText  taxonomy definition guiding classification
     * @param targetMethods deterministically extracted JUnit test methods that must
     *                      be classified
     *
     * @return normalized AI classification result
     *
     * @throws AiSuggestionException if the provider request fails, the response
     *                               cannot be parsed, or the provider returns
     *                               invalid content
     */
    @Override
    public AiClassSuggestion suggestForClass(String fqcn, String classSource, String taxonomyText,
            List<PromptBuilder.TargetMethod> targetMethods) throws AiSuggestionException {
        try {
            String prompt = PromptBuilder.build(fqcn, classSource, taxonomyText, targetMethods, options.confidence());

            MessageRequest payload = new MessageRequest(options.modelName(), SYSTEM_PROMPT,
                    List.of(new ContentMessage("user", List.of(new ContentBlock("text", prompt)))), 0.0, 2_000);

            String requestBody = httpSupport.objectMapper().writeValueAsString(payload);
            URI uri = URI.create(options.baseUrl() + "/v1/messages");

            HttpRequest request = httpSupport.jsonPost(uri, requestBody, options.timeout())
                    .header("x-api-key", options.resolvedApiKey()).header("anthropic-version", "2023-06-01").build();

            String responseBody = httpSupport.postJson(request);
            MessageResponse response = httpSupport.objectMapper().readValue(responseBody, MessageResponse.class);

            if (response.content() == null || response.content().isEmpty()) {
                throw new AiSuggestionException("No content returned by Anthropic");
            }

            String text = response.content().stream().filter(block -> "text".equals(block.type())).map(ResponseBlock::text)
                    .filter(value -> value != null && !value.isBlank()).findFirst()
                    .orElseThrow(() -> new AiSuggestionException("Anthropic returned no text block"));

            String json = JsonText.extractFirstJsonObject(text);
            AiClassSuggestion suggestion = httpSupport.objectMapper().readValue(json, AiClassSuggestion.class);
            return normalize(suggestion);

        } catch (Exception e) { // NOPMD
            throw new AiSuggestionException("Anthropic suggestion failed for " + fqcn, e);
        }
    }

    /**
     * Normalizes AI results returned by the provider.
     *
     * <p>
     * This method ensures that collection fields are never {@code null} and removes
     * malformed method entries that do not contain a valid method name.
     * </p>
     *
     * <p>
     * The normalization step protects the rest of the application from
     * provider-side inconsistencies and guarantees that the resulting
     * {@link AiClassSuggestion} object satisfies the expected invariants.
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
                        method.displayName(), method.tags() == null ? List.of() : method.tags(), method.reason(),
                        method.confidence()))
                .toList();

        return new AiClassSuggestion(input.className(), input.classSecurityRelevant(), classTags, input.classReason(),
                normalizedMethods);
    }

    /**
     * Request payload sent to the Anthropic message API.
     *
     * <p>
     * This record models the JSON structure expected by the {@code /v1/messages}
     * endpoint and is serialized using Jackson before transmission.
     * </p>
     *
     * @param model       model identifier
     * @param system      system prompt controlling model behavior
     * @param messages    list of message objects forming the conversation
     * @param temperature sampling temperature
     * @param maxTokens   maximum token count for the response
     */
    private record MessageRequest(String model, String system, List<ContentMessage> messages, Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens) {
    }

    /**
     * Message container used by the Anthropic message API.
     *
     * @param role    role of the message sender (for example {@code user})
     * @param content message content blocks
     */
    private record ContentMessage(String role, List<ContentBlock> content) {
    }

    /**
     * Individual content block within a message payload.
     *
     * @param type block type (for example {@code text})
     * @param text textual content of the block
     */
    private record ContentBlock(String type, String text) {
    }

    /**
     * Partial response model returned by the Anthropic API.
     *
     * <p>
     * Only the fields required by this client are mapped. Additional fields are
     * ignored to maintain forward compatibility with API changes.
     * </p>
     *
     * @param content list of content blocks in the response
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MessageResponse(List<ResponseBlock> content) {
    }

    /**
     * Content block returned within a provider response.
     *
     * <p>
     * The client scans these blocks to locate the first text segment containing the
     * JSON classification result.
     * </p>
     *
     * @param type block type (for example {@code text})
     * @param text textual content of the block
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseBlock(String type, String text) {
    }
}