// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.ai;

import java.net.http.HttpRequest;

/**
 * Shared chat-call orchestration for the four AI provider clients.
 *
 * <p>
 * Each provider's {@code suggestForClass} call follows the same five-step
 * sequence: serialise a provider-specific request payload to JSON, POST it
 * to the provider's endpoint, deserialise the response into a
 * provider-specific record, extract the inner JSON text containing the
 * classification, and parse that text into a normalised
 * {@link AiClassSuggestion}. Only the request shape, the URL, the auth
 * headers, and the response shape vary; the surrounding flow is identical.
 * </p>
 *
 * <p>
 * This executor hosts that surrounding flow. Each provider supplies the
 * variable bits as a {@link HttpRequest} and a typed extractor function.
 * The executor handles serialisation, deserialisation, normalisation, and
 * error wrapping so that the provider records stay focused on what is
 * genuinely unique to them: the JSON schema of the request and response,
 * and the auth conventions of the upstream service.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * This class is thread-safe to the extent that the supplied
 * {@link HttpSupport} is. The injected {@code HttpSupport} owns its
 * {@code HttpClient} and {@code ObjectMapper}, both of which are
 * thread-safe.
 * </p>
 *
 * @see AiProviderClient
 * @see HttpSupport
 * @since 1.0.0
 */
public final class HttpJsonExecutor {

    private final HttpSupport httpSupport;

    /**
     * Creates a new executor backed by {@code httpSupport}.
     *
     * @param httpSupport HTTP and JSON support layer; must not be {@code null}
     */
    public HttpJsonExecutor(HttpSupport httpSupport) {
        this.httpSupport = httpSupport;
    }

    /**
     * Returns the HTTP and JSON support layer used by this executor. Exposed
     * so provider clients can construct their request payloads and access
     * the shared {@code ObjectMapper} without duplicating it.
     *
     * @return the backing {@link HttpSupport}
     */
    public HttpSupport httpSupport() {
        return httpSupport;
    }

    /**
     * Executes a fully-prepared chat-completion request against an AI provider
     * and returns the normalised classification result.
     *
     * <p>
     * The pre-built {@code request} carries the provider's URL, auth headers,
     * and serialised request body. The executor sends it, deserialises the
     * response into {@code responseType}, hands the deserialised value to
     * {@code contentExtractor} to pull out the inner JSON text containing
     * the classification, parses that text into an
     * {@link AiClassSuggestion}, and applies
     * {@link AiProviderClient#normalize(AiClassSuggestion)} before returning.
     * </p>
     *
     * <p>
     * Any failure during the HTTP send, JSON parsing, or content extraction
     * is wrapped in an {@link AiSuggestionException} whose message names the
     * provider and the offending class so users can diagnose from the CLI.
     * </p>
     *
     * @param providerName       human-readable provider name, embedded in
     *                           error messages ({@code "Ollama"},
     *                           {@code "Anthropic"}, etc.); must not be
     *                           {@code null}
     * @param fqcn               fully qualified class name being classified,
     *                           embedded in error messages so users can
     *                           diagnose from the CLI; must not be
     *                           {@code null}
     * @param request            fully-prepared HTTP POST request including
     *                           URL, headers, and serialised JSON body;
     *                           must not be {@code null}
     * @param responseType       Jackson {@code Class} used to deserialise
     *                           the response body; must not be {@code null}
     * @param contentExtractor   function extracting the inner JSON text
     *                           from the deserialised response; should
     *                           return {@code null} or blank when the
     *                           provider returned no usable content; must
     *                           not be {@code null}
     * @param <RESP>             provider-specific response DTO type
     * @return normalised classification result; never {@code null}
     * @throws AiSuggestionException if the HTTP call fails, the response
     *                               cannot be parsed, or the extracted
     *                               content is empty or malformed
     */
    public <RESP> AiClassSuggestion execute(String providerName, String fqcn, HttpRequest request,
            Class<RESP> responseType, ContentExtractor<RESP> contentExtractor) throws AiSuggestionException {
        try {
            String responseBody = httpSupport.postJson(request);
            RESP response = httpSupport.objectMapper().readValue(responseBody, responseType);

            String content = contentExtractor.extract(response);

            String json = JsonText.extractFirstJsonObject(content);
            AiClassSuggestion suggestion = httpSupport.objectMapper().readValue(json, AiClassSuggestion.class);
            return AiProviderClient.normalize(suggestion);

        } catch (Exception e) {
            // The original per-provider code wrapped every failure (including an already-typed
            // AiSuggestionException) into a single user-facing "<Provider> suggestion failed for X"
            // message; the catch is intentionally broad to preserve that contract here.
            throw new AiSuggestionException(providerName + " suggestion failed for " + fqcn, e);
        }
    }

    /**
     * Strategy for extracting the inner JSON-classification text from a
     * provider-specific deserialised response.
     *
     * <p>
     * Modelled as a checked-exception functional interface so each provider
     * can throw {@link AiSuggestionException} with a precise diagnostic
     * message — for example "No choices returned by model" or "Anthropic
     * returned no text block" — instead of being forced to return
     * {@code null} and lose that specificity.
     * </p>
     *
     * @param <RESP> provider-specific response DTO type
     */
    @FunctionalInterface
    public interface ContentExtractor<RESP> {

        /**
         * Returns the inner JSON-classification text contained in
         * {@code response}, or throws when the response has no usable
         * content.
         *
         * @param response deserialised provider response; never {@code null}
         * @return non-blank inner JSON text
         * @throws AiSuggestionException if the response carries no usable
         *                               content; the message becomes the
         *                               diagnostic cause that the executor
         *                               wraps into the user-facing error
         */
        String extract(RESP response) throws AiSuggestionException;
    }
}
