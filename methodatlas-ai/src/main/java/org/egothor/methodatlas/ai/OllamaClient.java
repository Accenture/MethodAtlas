// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.ai;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@link AiProviderClient} implementation for a locally running
 * <a href="https://ollama.ai/">Ollama</a> inference service.
 *
 * <p>
 * This client submits taxonomy-guided classification prompts to the Ollama
 * HTTP API and converts the returned model response into the internal
 * {@link AiClassSuggestion} representation used by the MethodAtlas AI
 * subsystem.
 * </p>
 *
 * <h2>Operational responsibilities</h2>
 *
 * <ul>
 *   <li>verifying local Ollama availability via a lightweight probe</li>
 *   <li>constructing chat-style inference requests against {@code /api/chat}</li>
 *   <li>injecting the system prompt and the taxonomy-guided user prompt</li>
 *   <li>extracting and normalising the JSON classification result</li>
 * </ul>
 *
 * <p>
 * Intended primarily for local, offline, or privacy-preserving inference
 * scenarios where source code should not leave the host.
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
 * @see AiProviderFactory
 * @see HttpJsonExecutor
 * @since 1.0.0
 */
public record OllamaClient(AiOptions options, HttpJsonExecutor executor) implements AiProviderClient {

    /**
     * System prompt used to enforce deterministic, machine-readable model
     * output. The prompt instructs the model to behave as a strict
     * classification engine and to return JSON only, so the response can be
     * parsed automatically without dealing with markdown fences or commentary.
     */
    private static final String SYSTEM_PROMPT = """
            You are a precise software security classification engine.
            You classify JUnit 5 tests and return strict JSON only.
            Never include markdown fences, explanations, or extra text.
            """;

    /**
     * Creates an Ollama client with no rate-limit notification. Rate-limit
     * pauses are handled transparently. Use
     * {@link #OllamaClient(AiOptions, RateLimitListener)} when callers need to
     * be notified of such pauses.
     *
     * @param options AI runtime configuration
     */
    public OllamaClient(AiOptions options) {
        this(options, (waited, attempt, message) -> { });
    }

    /**
     * Creates an Ollama client that notifies {@code rateLimitListener} before
     * each rate-limit sleep.
     *
     * @param options           AI runtime configuration
     * @param rateLimitListener callback invoked before each HTTP&nbsp;429
     *                          pause; must not be {@code null}
     * @see RateLimitListener
     */
    public OllamaClient(AiOptions options, RateLimitListener rateLimitListener) {
        this(options, new HttpJsonExecutor(
                new HttpSupport(options.timeout(), options.maxRetries(), rateLimitListener)));
    }

    /**
     * Probes the Ollama service via the {@code /api/tags} endpoint. Any
     * exception raised during the probe — connection refused, timeout, DNS
     * failure — is treated as "unavailable" and reported as {@code false}
     * rather than propagated; the orchestration layer chooses an alternative
     * provider on {@link AiProvider#AUTO}.
     *
     * @return {@code true} if the Ollama service responded; {@code false}
     *         otherwise
     */
    @Override
    public boolean isAvailable() {
        try {
            URI uri = URI.create(options.baseUrl() + "/api/tags");
            HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(options.timeout()).build();
            executor.httpSupport().httpClient().send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public AiClassSuggestion suggestForClass(String fqcn, String prompt) throws AiSuggestionException {
        HttpRequest request;
        try {
            ChatRequest payload = new ChatRequest(options.modelName(),
                    List.of(new Message("system", SYSTEM_PROMPT), new Message("user", prompt)),
                    false, new Options(0.0));
            String requestBody = executor.httpSupport().objectMapper().writeValueAsString(payload);
            URI uri = URI.create(options.baseUrl() + "/api/chat");
            request = executor.httpSupport().jsonPost(uri, requestBody, options.timeout()).build();
        } catch (Exception e) {
            throw new AiSuggestionException("Ollama suggestion failed for " + fqcn, e);
        }
        return executor.execute("Ollama", fqcn, request, ChatResponse.class, response -> {
            if (response.message() == null || response.message().content() == null
                    || response.message().content().isBlank()) {
                throw new AiSuggestionException("Ollama returned no message content");
            }
            return response.message().content();
        });
    }

    /**
     * Request payload sent to the Ollama chat API. Models the JSON structure
     * expected by the {@code /api/chat} endpoint.
     *
     * @param model    model identifier used for inference
     * @param messages ordered chat messages sent to the model
     * @param stream   whether streaming responses are requested
     * @param options  provider-specific inference options
     */
    private record ChatRequest(String model, List<Message> messages, boolean stream, Options options) { }

    /**
     * Chat message sent to the Ollama API.
     *
     * @param role    logical role of the message sender ({@code system}, {@code user})
     * @param content textual message content
     */
    private record Message(String role, String content) { }

    /**
     * Provider-specific inference options supplied to the Ollama API.
     *
     * <p>
     * Only the {@code temperature} sampling parameter is configured.
     * MethodAtlas sets temperature to {@code 0.0} to obtain stable, repeatable
     * classification results and strictly formatted JSON. Stochastic sampling
     * would risk markdown fences, formatting variations, and malformed JSON
     * that would break downstream parsing.
     * </p>
     *
     * @param temperature sampling temperature controlling response randomness
     */
    private record Options(@JsonProperty("temperature") Double temperature) { }

    /**
     * Partial response model returned by the Ollama chat API. Unknown
     * properties are ignored for forward compatibility with future API
     * extensions.
     *
     * @param message the response message payload
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatResponse(ResponseMessage message) { }

    /**
     * Message payload returned within an Ollama chat response. The client
     * reads the {@code content} component and expects it to contain the
     * JSON classification result generated by the model.
     *
     * @param content the textual content of the message
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseMessage(String content) { }
}
