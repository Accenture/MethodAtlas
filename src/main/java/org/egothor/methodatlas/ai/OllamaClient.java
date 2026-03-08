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
 * This client submits taxonomy-guided classification prompts to the Ollama HTTP
 * API and converts the returned model response into the internal
 * {@link AiClassSuggestion} representation used by the MethodAtlas AI
 * subsystem.
 * </p>
 *
 * <h2>Operational Responsibilities</h2>
 *
 * <ul>
 * <li>verifying local Ollama availability</li>
 * <li>constructing chat-style inference requests</li>
 * <li>injecting the system prompt and taxonomy-guided user prompt</li>
 * <li>executing HTTP requests against the Ollama API</li>
 * <li>extracting and normalizing JSON classification results</li>
 * </ul>
 *
 * <p>
 * The client uses the Ollama {@code /api/chat} endpoint for inference and the
 * {@code /api/tags} endpoint as a lightweight availability probe.
 * </p>
 *
 * <p>
 * This implementation is intended primarily for local, offline, or
 * privacy-preserving inference scenarios where source code should not be sent
 * to an external provider.
 * </p>
 *
 * @see AiProviderClient
 * @see AiProviderFactory
 * @see AiSuggestionEngine
 */
public final class OllamaClient implements AiProviderClient {
    /**
     * System prompt used to enforce deterministic, machine-readable model output.
     *
     * <p>
     * The prompt instructs the model to behave as a strict classification engine
     * and to return JSON only, without markdown fences or explanatory prose, so
     * that the response can be parsed automatically.
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
     * Creates a new Ollama client using the supplied runtime configuration.
     *
     * <p>
     * The configuration determines the base URL of the Ollama service, the model
     * identifier, and request timeout values used by this client.
     * </p>
     *
     * @param options AI runtime configuration
     */
    public OllamaClient(AiOptions options) {
        this.options = options;
        this.httpSupport = new HttpSupport(options.timeout());
    }

    /**
     * Determines whether the configured Ollama service is reachable.
     *
     * <p>
     * The method performs a lightweight availability probe against the
     * {@code /api/tags} endpoint. If the endpoint responds successfully, the
     * provider is considered available.
     * </p>
     *
     * <p>
     * Any exception raised during the probe is treated as an indication that the
     * provider is unavailable.
     * </p>
     *
     * @return {@code true} if the Ollama service is reachable; {@code false}
     *         otherwise
     */
    @Override
    public boolean isAvailable() {
        try {
            URI uri = URI.create(options.baseUrl() + "/api/tags");
            HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(options.timeout()).build();

            httpSupport.httpClient().send(request, HttpResponse.BodyHandlers.discarding());

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Submits a classification request to the Ollama chat API for the specified
     * test class.
     *
     * <p>
     * The request consists of:
     * </p>
     * <ul>
     * <li>a system prompt enforcing strict JSON output</li>
     * <li>a user prompt containing the test class source and taxonomy text</li>
     * <li>provider options such as deterministic temperature settings</li>
     * </ul>
     *
     * <p>
     * The returned response is expected to contain a JSON object in the message
     * content field. That JSON text is extracted, deserialized into an
     * {@link AiClassSuggestion}, and then normalized before being returned.
     * </p>
     *
     * @param fqcn         fully qualified class name being analyzed
     * @param classSource  complete source code of the class being analyzed
     * @param taxonomyText taxonomy definition guiding classification
     * @return normalized AI classification result
     *
     * @throws AiSuggestionException if the request fails, if the provider returns
     *                               invalid content, or if response deserialization
     *                               fails
     */
    @Override
    public AiClassSuggestion suggestForClass(String fqcn, String classSource, String taxonomyText)
            throws AiSuggestionException {
        try {
            String prompt = PromptBuilder.build(fqcn, classSource, taxonomyText);

            ChatRequest payload = new ChatRequest(options.modelName(),
                    List.of(new Message("system", SYSTEM_PROMPT), new Message("user", prompt)), false,
                    new Options(0.0));

            String requestBody = httpSupport.objectMapper().writeValueAsString(payload);
            URI uri = URI.create(options.baseUrl() + "/api/chat");

            HttpRequest request = httpSupport.jsonPost(uri, requestBody, options.timeout()).build();
            String responseBody = httpSupport.postJson(request);
            ChatResponse response = httpSupport.objectMapper().readValue(responseBody, ChatResponse.class);

            if (response.message == null || response.message.content == null || response.message.content.isBlank()) {
                throw new AiSuggestionException("Ollama returned no message content");
            }

            String json = JsonText.extractFirstJsonObject(response.message.content);
            AiClassSuggestion suggestion = httpSupport.objectMapper().readValue(json, AiClassSuggestion.class);
            return normalize(suggestion);

        } catch (Exception e) {
            throw new AiSuggestionException("Ollama suggestion failed for " + fqcn, e);
        }
    }

    /**
     * Normalizes a provider response into the application's internal result
     * invariants.
     *
     * <p>
     * The method ensures that collection-valued fields are never {@code null} and
     * removes malformed method entries that do not define a usable method name.
     * </p>
     *
     * @param input raw suggestion returned by the provider
     * @return normalized suggestion
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
     * Request payload sent to the Ollama chat API.
     *
     * <p>
     * This record models the JSON structure expected by the {@code /api/chat}
     * endpoint.
     * </p>
     *
     * @param model    model identifier used for inference
     * @param messages ordered chat messages sent to the model
     * @param stream   whether streaming responses are requested
     * @param options  provider-specific inference options
     */
    private record ChatRequest(String model, List<Message> messages, boolean stream, Options options) {
    }

    /**
     * Chat message sent to the Ollama API.
     *
     * @param role    logical role of the message sender, such as {@code system} or
     *                {@code user}
     * @param content textual message content
     */
    private record Message(String role, String content) {
    }

    /**
     * Provider-specific inference options supplied to the Ollama API.
     *
     * <p>
     * Currently only the {@code temperature} sampling parameter is configured.
     * Temperature controls the randomness of model output:
     * </p>
     *
     * <ul>
     * <li>{@code 0.0} produces deterministic output</li>
     * <li>higher values increase variation and creativity</li>
     * </ul>
     *
     * <p>
     * The MethodAtlas AI integration explicitly sets {@code temperature} to
     * {@code 0.0} in order to obtain stable, repeatable classification results and
     * strictly formatted JSON output suitable for automated parsing.
     * </p>
     *
     * <p>
     * Allowing stochastic sampling would significantly increase the probability
     * that the model produces explanatory text, formatting variations, or malformed
     * JSON responses, which would break the downstream deserialization pipeline.
     * </p>
     *
     * @param temperature sampling temperature controlling response randomness
     */
    private record Options(@JsonProperty("temperature") Double temperature) {
    }

    /**
     * Partial response model returned by the Ollama chat API.
     *
     * <p>
     * Only the fields required by this client are modeled. Unknown properties are
     * ignored to maintain compatibility with future API extensions.
     * </p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ChatResponse {
        public ResponseMessage message;
    }

    /**
     * Message payload returned within an Ollama chat response.
     *
     * <p>
     * The client reads the {@link #content} field and expects it to contain the
     * JSON classification result generated by the model.
     * </p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ResponseMessage {
        public String content;
    }
}