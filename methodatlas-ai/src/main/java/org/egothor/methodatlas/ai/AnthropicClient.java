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
 * {@link AiProviderClient} implementation for the Anthropic
 * <a href="https://docs.anthropic.com/">Claude API</a>.
 *
 * <p>
 * Submits classification requests to the {@code /v1/messages} endpoint using
 * Anthropic's message format: a system prompt defines the classification
 * rules and the user message contains the class source together with the
 * taxonomy specification. The first {@code text} block of the response is
 * extracted and parsed as the JSON classification.
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
 * @see AiProviderClient
 * @see AiSuggestionEngine
 * @see AiProviderFactory
 * @see HttpJsonExecutor
 * @since 1.0.0
 */
public record AnthropicClient(AiOptions options, HttpJsonExecutor executor) implements AiProviderClient {

    /**
     * System prompt enforcing deterministic JSON output. Forbids
     * explanations, markdown formatting, or conversational responses that
     * would break the JSON-extraction pipeline.
     */
    private static final String SYSTEM_PROMPT = """
            You are a precise software security classification engine.
            You classify JUnit 5 tests and return strict JSON only.
            Never include markdown fences, explanations, or extra text.
            """;

    /**
     * Creates an Anthropic client with no rate-limit notification.
     *
     * @param options AI runtime configuration
     */
    public AnthropicClient(AiOptions options) {
        this(options, (waited, attempt, message) -> { });
    }

    /**
     * Creates an Anthropic client that notifies {@code rateLimitListener}
     * before each rate-limit sleep.
     *
     * @param options           AI runtime configuration
     * @param rateLimitListener callback invoked before each HTTP&nbsp;429
     *                          pause; must not be {@code null}
     * @see RateLimitListener
     */
    public AnthropicClient(AiOptions options, RateLimitListener rateLimitListener) {
        this(options, new HttpJsonExecutor(
                new HttpSupport(options.timeout(), options.maxRetries(), rateLimitListener)));
    }

    /**
     * Availability is determined by the presence of a usable API key
     * resolved through {@link AiOptions#resolvedApiKey()}.
     *
     * @return {@code true} if a usable API key is configured
     */
    @Override
    public boolean isAvailable() {
        String key = options.resolvedApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public AiClassSuggestion suggestForClass(String fqcn, String prompt) throws AiSuggestionException {
        HttpRequest request;
        try {
            MessageRequest payload = new MessageRequest(options.modelName(), SYSTEM_PROMPT,
                    List.of(new ContentMessage("user", List.of(new ContentBlock("text", prompt)))),
                    0.0, 2_000);
            String requestBody = executor.httpSupport().objectMapper().writeValueAsString(payload);
            URI uri = URI.create(options.baseUrl() + "/v1/messages");

            request = executor.httpSupport().jsonPost(uri, requestBody, options.timeout())
                    .header("x-api-key", options.resolvedApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .build();
        } catch (Exception e) {
            throw new AiSuggestionException("Anthropic suggestion failed for " + fqcn, e);
        }

        return executor.execute("Anthropic", fqcn, request, MessageResponse.class,
                AnthropicClient::extractContent);
    }

    /**
     * Extracts the first non-blank text block from an Anthropic response.
     * Distinguishes two empty-content failure modes with separate diagnostic
     * messages: "No content returned by Anthropic" when the content list is
     * empty, and "Anthropic returned no text block" when the content list
     * contains only non-text blocks.
     *
     * @param response deserialised Anthropic response
     * @return text content of the first usable text block
     * @throws AiSuggestionException when no usable text content is present
     */
    private static String extractContent(MessageResponse response) throws AiSuggestionException {
        if (response.content() == null || response.content().isEmpty()) {
            throw new AiSuggestionException("No content returned by Anthropic");
        }
        return response.content().stream()
                .filter(block -> "text".equals(block.type()))
                .map(ResponseBlock::text)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new AiSuggestionException("Anthropic returned no text block"));
    }

    /**
     * Request payload sent to the Anthropic message API. Models the JSON
     * structure expected by the {@code /v1/messages} endpoint and is
     * serialised using Jackson before transmission.
     *
     * @param model       model identifier
     * @param system      system prompt controlling model behaviour
     * @param messages    list of message objects forming the conversation
     * @param temperature sampling temperature
     * @param maxTokens   maximum token count for the response
     */
    private record MessageRequest(String model, String system, List<ContentMessage> messages, Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens) { }

    /**
     * Message container used by the Anthropic message API.
     *
     * @param role    role of the message sender (for example {@code user})
     * @param content message content blocks
     */
    private record ContentMessage(String role, List<ContentBlock> content) { }

    /**
     * Individual content block within a message payload.
     *
     * @param type block type (for example {@code text})
     * @param text textual content of the block
     */
    private record ContentBlock(String type, String text) { }

    /**
     * Partial response model returned by the Anthropic API. Only the fields
     * required by this client are mapped; additional fields are ignored to
     * maintain forward compatibility with API changes.
     *
     * @param content list of content blocks in the response
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MessageResponse(List<ResponseBlock> content) { }

    /**
     * Content block returned within a provider response. The client scans
     * these blocks to locate the first {@code text} segment containing the
     * JSON classification result.
     *
     * @param type block type (for example {@code text})
     * @param text textual content of the block
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseBlock(String type, String text) { }
}
