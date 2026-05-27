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
 * {@link AiProviderClient} implementation for AI providers that expose an
 * OpenAI-compatible chat-completion API.
 *
 * <p>
 * Supports the broad family of providers that follow the OpenAI protocol:
 * OpenAI itself, OpenRouter, Groq, Mistral, GitHub Models, xAI. The path
 * appended to the configured base URL is provider-specific:
 * {@code /v1/chat/completions} for most providers; {@code /chat/completions}
 * for {@link AiProvider#GITHUB_MODELS}, {@link AiProvider#XAI}, and
 * {@link AiProvider#MISTRAL} because their default base URLs already include
 * (or, in the case of GitHub Models, deliberately omit) the {@code /v1}
 * segment.
 * </p>
 *
 * <h2>Record components</h2>
 *
 * <ul>
 *   <li>{@code options}  — AI runtime configuration; never {@code null}</li>
 *   <li>{@code executor} — shared HTTP-and-JSON orchestrator; never {@code null}</li>
 * </ul>
 *
 * @param options  AI runtime configuration
 * @param executor shared HTTP-and-JSON orchestrator
 * @see AiProvider
 * @see AiProviderClient
 * @see AiProviderFactory
 * @see HttpJsonExecutor
 * @since 1.0.0
 */
public record OpenAiCompatibleClient(AiOptions options, HttpJsonExecutor executor) implements AiProviderClient {

    /**
     * System prompt instructing the model to operate strictly as a
     * classification engine and to return machine-readable JSON output.
     * Forbids explanatory text and markdown formatting so the response can
     * be parsed reliably.
     */
    private static final String SYSTEM_PROMPT = """
            You are a precise software security classification engine.
            You classify JUnit 5 tests and return strict JSON only.
            Never include markdown fences, explanations, or extra text.
            """;

    /**
     * Creates a client for an OpenAI-compatible provider with no rate-limit
     * notification.
     *
     * @param options AI runtime configuration
     */
    public OpenAiCompatibleClient(AiOptions options) {
        this(options, (waited, attempt, message) -> { });
    }

    /**
     * Creates a client for an OpenAI-compatible provider that notifies
     * {@code rateLimitListener} before each rate-limit sleep.
     *
     * @param options           AI runtime configuration
     * @param rateLimitListener callback invoked before each HTTP&nbsp;429
     *                          pause; must not be {@code null}
     * @see RateLimitListener
     */
    public OpenAiCompatibleClient(AiOptions options, RateLimitListener rateLimitListener) {
        this(options, new HttpJsonExecutor(
                new HttpSupport(options.timeout(), options.maxRetries(), rateLimitListener)));
    }

    /**
     * Availability for OpenAI-compatible providers is determined by the
     * presence of a usable API key resolved through
     * {@link AiOptions#resolvedApiKey()} — the call is otherwise pre-flight
     * inexpensive (no network probe).
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
            URI uri = URI.create(options.baseUrl() + chatCompletionsPath(options.provider()));

            HttpRequest.Builder requestBuilder = executor.httpSupport().jsonPost(uri, requestBody, options.timeout())
                    .header("Authorization", "Bearer " + options.resolvedApiKey());

            if (options.provider() == AiProvider.OPENROUTER) {
                requestBuilder.header("HTTP-Referer", "https://methodatlas.local");
                requestBuilder.header("X-Title", "MethodAtlas");
            }

            request = requestBuilder.build();
        } catch (Exception e) { // NOPMD - payload serialisation failure
            throw new AiSuggestionException("OpenAI-compatible suggestion failed for " + fqcn, e);
        }

        return executor.execute("OpenAI-compatible", fqcn, request, ChatResponse.class, response -> {
            if (response.choices() == null || response.choices().isEmpty()) {
                throw new AiSuggestionException("No choices returned by model");
            }
            return response.choices().get(0).message().content();
        });
    }

    /**
     * Returns the chat-completions URL path for the given provider. Most
     * OpenAI-compatible providers expose their completions endpoint at
     * {@code /v1/chat/completions} relative to their base URL. GitHub
     * Models, xAI, and Mistral already embed {@code /v1} in their default
     * base URL (or, for GitHub Models, deliberately omit version segments),
     * so they use the shorter path here to avoid producing a
     * double-versioned URL.
     *
     * @param provider the active provider
     * @return the path component to append to the base URL
     */
    private static String chatCompletionsPath(AiProvider provider) {
        return switch (provider) {
            case GITHUB_MODELS, XAI, MISTRAL -> "/chat/completions";
            default -> "/v1/chat/completions";
        };
    }

    /**
     * Request payload for an OpenAI-compatible chat-completion request.
     *
     * @param model       model identifier used for inference
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
     * Partial response model returned by the chat-completion API. Only fields
     * required for extracting the model response are mapped; unknown
     * properties are ignored to preserve compatibility with provider API
     * changes.
     *
     * @param choices list of completion choices returned by the provider
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatResponse(List<Choice> choices) { }

    /**
     * Individual completion choice returned by the provider.
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
