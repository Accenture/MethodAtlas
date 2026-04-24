package org.egothor.methodatlas.ai;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@link AiProviderClient} implementation for
 * <a href="https://azure.microsoft.com/en-us/products/ai-services/openai-service">Azure
 * OpenAI Service</a> deployments.
 *
 * <p>
 * Azure OpenAI exposes a chat completions API that is structurally similar to
 * the public OpenAI API but differs in three important ways:
 * </p>
 *
 * <ul>
 * <li><strong>Endpoint structure</strong> — the deployment name is embedded in
 *     the path rather than supplied as a JSON field:
 *     {@code {baseUrl}/openai/deployments/{deployment}/chat/completions?api-version={version}}</li>
 * <li><strong>Authentication header</strong> — requests carry an {@code api-key}
 *     header instead of the standard {@code Authorization: Bearer} form used by
 *     the public OpenAI API</li>
 * <li><strong>Model identifier</strong> — {@link AiOptions#modelName()} is
 *     interpreted as the Azure <em>deployment name</em>, not the underlying model
 *     family name; the deployment name is chosen when the resource is configured
 *     in the Azure portal</li>
 * </ul>
 *
 * <p>
 * These differences are fully encapsulated within this class. The request and
 * response JSON structures are identical to those used by
 * {@link OpenAiCompatibleClient}, allowing the same prompt builder and response
 * normalization logic to be reused.
 * </p>
 *
 * <h2>Data Residency</h2>
 *
 * <p>
 * Requests are sent to a resource endpoint within the organization's own Azure
 * tenant. Data does not leave the tenant boundary, making this provider
 * suitable for regulated environments where source code must not be transmitted
 * to third-party cloud services.
 * </p>
 *
 * <h2>Operational Responsibilities</h2>
 *
 * <ul>
 * <li>constructing the Azure-specific deployment endpoint URL</li>
 * <li>injecting the {@code api-key} authentication header</li>
 * <li>constructing and submitting chat completion requests</li>
 * <li>extracting JSON content from the model response</li>
 * <li>normalizing the result into {@link AiClassSuggestion}</li>
 * </ul>
 *
 * <p>
 * Instances are typically created through
 * {@link AiProviderFactory#create(AiOptions)}.
 * </p>
 *
 * @see AiProvider#AZURE_OPENAI
 * @see AiProviderClient
 * @see AiProviderFactory
 * @see OpenAiCompatibleClient
 */
public final class AzureOpenAiClient implements AiProviderClient {

    private static final String SYSTEM_PROMPT = """
            You are a precise software security classification engine.
            You classify JUnit 5 tests and return strict JSON only.
            Never include markdown fences, explanations, or extra text.
            """;

    private final AiOptions options;
    private final HttpSupport httpSupport;

    /**
     * Creates a new client for an Azure OpenAI Service deployment.
     *
     * <p>
     * The supplied configuration must provide:
     * </p>
     *
     * <ul>
     * <li>{@link AiOptions#baseUrl()} — resource endpoint, e.g.
     *     {@code https://contoso.openai.azure.com}</li>
     * <li>{@link AiOptions#modelName()} — deployment name as configured in the
     *     Azure portal</li>
     * <li>{@link AiOptions#apiVersion()} — REST API version, e.g.
     *     {@code 2024-02-01}</li>
     * <li>{@link AiOptions#resolvedApiKey()} — resource-scoped API key</li>
     * </ul>
     *
     * @param options AI runtime configuration
     */
    public AzureOpenAiClient(AiOptions options) {
        this.options = options;
        this.httpSupport = new HttpSupport(options.timeout());
    }

    /**
     * Determines whether this client can be used in the current runtime
     * environment.
     *
     * <p>
     * Availability requires a non-blank API key resolved through
     * {@link AiOptions#resolvedApiKey()}.
     * </p>
     *
     * @return {@code true} if a usable API key is available
     */
    @Override
    public boolean isAvailable() {
        String key = options.resolvedApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * Submits a classification request to the configured Azure OpenAI deployment.
     *
     * <p>
     * The request is sent to the deployment-specific endpoint:
     * </p>
     *
     * <pre>
     * {baseUrl}/openai/deployments/{modelName}/chat/completions?api-version={apiVersion}
     * </pre>
     *
     * <p>
     * The request payload includes:
     * </p>
     *
     * <ul>
     * <li>the deployment name as the {@code model} field</li>
     * <li>a system prompt defining classification rules</li>
     * <li>a user prompt containing the test class source and taxonomy</li>
     * <li>a deterministic temperature setting of {@code 0.0}</li>
     * </ul>
     *
     * <p>
     * Authentication uses the {@code api-key} HTTP header carrying the value
     * returned by {@link AiOptions#resolvedApiKey()}.
     * </p>
     *
     * @param fqcn          fully qualified class name being analyzed
     * @param classSource   complete source code of the class
     * @param taxonomyText  taxonomy definition guiding classification
     * @param targetMethods deterministically extracted JUnit test methods that must
     *                      be classified
     * @return normalized classification result
     *
     * @throws AiSuggestionException if the provider request fails, the model
     *                               response is invalid, or JSON deserialization
     *                               fails
     */
    @Override
    public AiClassSuggestion suggestForClass(String fqcn, String classSource, String taxonomyText,
            List<PromptBuilder.TargetMethod> targetMethods) throws AiSuggestionException {
        try {
            String prompt = PromptBuilder.build(fqcn, classSource, taxonomyText, targetMethods, options.confidence());

            ChatRequest payload = new ChatRequest(options.modelName(),
                    List.of(new Message("system", SYSTEM_PROMPT), new Message("user", prompt)), 0.0);

            String requestBody = httpSupport.objectMapper().writeValueAsString(payload);

            String url = options.baseUrl() + "/openai/deployments/" + options.modelName()
                    + "/chat/completions?api-version=" + options.apiVersion();
            URI uri = URI.create(url);

            HttpRequest request = httpSupport.jsonPost(uri, requestBody, options.timeout())
                    .header("api-key", options.resolvedApiKey())
                    .build();

            String responseBody = httpSupport.postJson(request);
            ChatResponse response = httpSupport.objectMapper().readValue(responseBody, ChatResponse.class);

            if (response.choices() == null || response.choices().isEmpty()) {
                throw new AiSuggestionException("No choices returned by Azure OpenAI deployment");
            }

            String content = response.choices().get(0).message().content();
            String json = JsonText.extractFirstJsonObject(content);
            AiClassSuggestion suggestion = httpSupport.objectMapper().readValue(json, AiClassSuggestion.class);
            return normalize(suggestion);

        } catch (Exception e) { // NOPMD
            throw new AiSuggestionException("Azure OpenAI suggestion failed for " + fqcn, e);
        }
    }

    /**
     * Normalizes provider results to ensure structural invariants expected by the
     * application.
     *
     * <p>
     * Replaces {@code null} collections with empty lists and removes malformed
     * method entries that do not contain a valid method name.
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
     * Request payload for the Azure OpenAI chat completions API.
     *
     * @param model       deployment name used for inference
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
     * Partial response model returned by the chat completions API.
     *
     * <p>
     * Only fields required for extracting the model response are mapped. Unknown
     * properties are ignored to preserve compatibility with API version changes.
     * </p>
     *
     * @param choices list of completion choices returned by the deployment
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatResponse(List<Choice> choices) {
    }

    /**
     * Individual completion choice returned by the deployment.
     *
     * @param message the message payload contained in this choice
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(ResponseMessage message) {
    }

    /**
     * Message payload returned inside a completion choice.
     *
     * <p>
     * The {@code content} component is expected to contain the JSON classification
     * result generated by the model.
     * </p>
     *
     * @param content the textual content of the message
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseMessage(String content) {
    }
}
