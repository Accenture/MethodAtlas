// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
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
 * Azure OpenAI exposes a chat-completions API that is structurally similar
 * to the public OpenAI API but differs in three important ways:
 * </p>
 *
 * <ul>
 *   <li><strong>Endpoint structure</strong> — the deployment name is embedded
 *       in the path rather than supplied as a JSON field:
 *       {@code {baseUrl}/openai/deployments/{deployment}/chat/completions?api-version={version}}</li>
 *   <li><strong>Authentication header</strong> — requests carry an
 *       {@code api-key} header instead of the {@code Authorization: Bearer}
 *       form used by the public OpenAI API</li>
 *   <li><strong>Model identifier</strong> — {@link AiOptions#modelName()} is
 *       interpreted as the Azure <em>deployment name</em>, not the
 *       underlying model family</li>
 * </ul>
 *
 * <h2>Data residency</h2>
 *
 * <p>
 * Requests are sent to a resource endpoint within the organisation's own
 * Azure tenant. Data does not leave the tenant boundary, making this provider
 * suitable for regulated environments where source code must not be
 * transmitted to third-party cloud services.
 * </p>
 *
 * <h2>Record components</h2>
 *
 * <ul>
 *   <li>{@code options}  — AI runtime configuration; must supply
 *       {@code baseUrl}, {@code modelName} (deployment name),
 *       {@code apiVersion}, and a resolvable API key</li>
 *   <li>{@code executor} — shared HTTP-and-JSON orchestrator; never {@code null}</li>
 * </ul>
 *
 * @param options  AI runtime configuration
 * @param executor shared HTTP-and-JSON orchestrator
 * @see AiProvider#AZURE_OPENAI
 * @see AiProviderClient
 * @see AiProviderFactory
 * @see OpenAiCompatibleClient
 * @see HttpJsonExecutor
 * @since 1.0.0
 */
public record AzureOpenAiClient(AiOptions options, HttpJsonExecutor executor) implements AiProviderClient {

    private static final String SYSTEM_PROMPT = """
            You are a precise software security classification engine.
            You classify JUnit 5 tests and return strict JSON only.
            Never include markdown fences, explanations, or extra text.
            """;

    /**
     * Creates an Azure OpenAI client with no rate-limit notification. The
     * supplied configuration must provide {@link AiOptions#baseUrl()} (resource
     * endpoint, for example {@code https://contoso.openai.azure.com}),
     * {@link AiOptions#modelName()} (deployment name as configured in the
     * Azure portal), {@link AiOptions#apiVersion()} (REST API version, for
     * example {@code 2024-02-01}), and {@link AiOptions#resolvedApiKey()}
     * (resource-scoped API key).
     *
     * @param options AI runtime configuration
     */
    public AzureOpenAiClient(AiOptions options) {
        this(options, (waited, attempt, message) -> { });
    }

    /**
     * Creates an Azure OpenAI client that notifies {@code rateLimitListener}
     * before each rate-limit sleep.
     *
     * @param options           AI runtime configuration
     * @param rateLimitListener callback invoked before each HTTP&nbsp;429
     *                          pause; must not be {@code null}
     * @see RateLimitListener
     */
    public AzureOpenAiClient(AiOptions options, RateLimitListener rateLimitListener) {
        this(options, new HttpJsonExecutor(
                new HttpSupport(options.timeout(), options.maxRetries(), rateLimitListener)));
    }

    /**
     * Availability is determined by the presence of a usable API key
     * resolved through {@link AiOptions#resolvedApiKey()}.
     *
     * @return {@code true} if a usable API key is available
     */
    @Override
    public boolean isAvailable() {
        String key = options.resolvedApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public AiClassSuggestion suggestForClass(String fqcn, String classSource, String taxonomyText,
            List<PromptBuilder.TargetMethod> targetMethods) throws AiSuggestionException {
        HttpRequest request;
        try {
            String prompt = PromptBuilder.build(fqcn, classSource, taxonomyText, targetMethods, options.confidence());
            ChatRequest payload = new ChatRequest(options.modelName(),
                    List.of(new Message("system", SYSTEM_PROMPT), new Message("user", prompt)), 0.0);
            String requestBody = executor.httpSupport().objectMapper().writeValueAsString(payload);

            String url = options.baseUrl() + "/openai/deployments/" + options.modelName()
                    + "/chat/completions?api-version=" + options.apiVersion();
            URI uri = URI.create(url);

            request = executor.httpSupport().jsonPost(uri, requestBody, options.timeout())
                    .header("api-key", options.resolvedApiKey())
                    .build();
        } catch (Exception e) { // NOPMD - payload serialisation failure
            throw new AiSuggestionException("Azure OpenAI suggestion failed for " + fqcn, e);
        }

        return executor.execute("Azure OpenAI", fqcn, request, ChatResponse.class, response -> {
            if (response.choices() == null || response.choices().isEmpty()) {
                throw new AiSuggestionException("No choices returned by Azure OpenAI deployment");
            }
            return response.choices().get(0).message().content();
        });
    }

    /**
     * Request payload for the Azure OpenAI chat-completions API.
     *
     * @param model       deployment name used for inference
     * @param messages    ordered chat messages sent to the model
     * @param temperature sampling temperature controlling response variability
     */
    private record ChatRequest(String model, List<Message> messages,
            @JsonProperty("temperature") Double temperature) { }

    /**
     * Chat message included in the request payload.
     *
     * @param role    logical role of the message sender ({@code system}, {@code user})
     * @param content textual message content
     */
    private record Message(String role, String content) { }

    /**
     * Partial response model returned by the chat-completions API. Only
     * fields required for extracting the model response are mapped; unknown
     * properties are ignored to preserve compatibility with API version
     * changes.
     *
     * @param choices list of completion choices returned by the deployment
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatResponse(List<Choice> choices) { }

    /**
     * Individual completion choice returned by the deployment.
     *
     * @param message the message payload contained in this choice
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(ResponseMessage message) { }

    /**
     * Message payload returned inside a completion choice. The {@code content}
     * component is expected to contain the JSON classification result
     * generated by the model.
     *
     * @param content the textual content of the message
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseMessage(String content) { }
}
