package org.egothor.methodatlas.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Small HTTP utility component used by AI provider clients for outbound network
 * communication and JSON processing support.
 *
 * <p>
 * This class centralizes common HTTP-related functionality required by the AI
 * provider integrations, including:
 * </p>
 * <ul>
 * <li>creation of a configured {@link HttpClient}</li>
 * <li>provision of a shared Jackson {@link ObjectMapper}</li>
 * <li>execution of JSON-oriented HTTP requests</li>
 * <li>construction of JSON {@code POST} requests</li>
 * </ul>
 *
 * <p>
 * The helper is intentionally lightweight and provider-agnostic. It does not
 * implement provider-specific authentication, endpoint selection, or response
 * normalization logic; those responsibilities remain in the concrete provider
 * clients.
 * </p>
 *
 * <p>
 * The internally managed {@link ObjectMapper} is configured to ignore unknown
 * JSON properties so that provider response deserialization remains resilient
 * to non-breaking API changes.
 * </p>
 *
 * <p>
 * Instances of this class are immutable after construction.
 * </p>
 *
 * @see HttpClient
 * @see ObjectMapper
 * @see AiProviderClient
 */
public final class HttpSupport {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new HTTP support helper with the specified connection timeout.
     *
     * <p>
     * The supplied timeout is used as the connection timeout of the underlying
     * {@link HttpClient}. Request-specific timeouts may still be configured
     * independently on individual {@link HttpRequest} instances.
     * </p>
     *
     * <p>
     * The constructor also initializes a Jackson {@link ObjectMapper} configured
     * with {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} disabled.
     * </p>
     *
     * @param timeout connection timeout used for the underlying HTTP client
     */
    public HttpSupport(Duration timeout) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();

        this.objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Returns the configured HTTP client used by this helper.
     *
     * @return configured HTTP client instance
     */
    public HttpClient httpClient() {
        return httpClient;
    }

    /**
     * Returns the configured Jackson object mapper used for JSON serialization and
     * deserialization.
     *
     * @return configured object mapper instance
     */
    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    /**
     * Executes an HTTP request expected to return a JSON response body and returns
     * the response content as text.
     *
     * <p>
     * The method sends the supplied request using the internally configured
     * {@link HttpClient}. Responses with HTTP status codes outside the successful
     * {@code 2xx} range are treated as failures and cause an {@link IOException} to
     * be thrown containing both the status code and response body.
     * </p>
     *
     * <p>
     * Despite the method name, the request itself is not required to be a
     * {@code POST} request; the method simply executes the provided request and
     * validates that the response indicates success.
     * </p>
     *
     * @param request HTTP request to execute
     * @return response body as text
     *
     * @throws IOException          if request execution fails or if the HTTP
     *                              response status code is outside the successful
     *                              {@code 2xx} range
     * @throws InterruptedException if the calling thread is interrupted while
     *                              waiting for the response
     */
    public String postJson(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();

        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("HTTP " + statusCode + ": " + response.body());
        }

        return response.body();
    }

    /**
     * Creates a JSON-oriented HTTP {@code POST} request builder.
     *
     * <p>
     * The returned builder is preconfigured with:
     * </p>
     * <ul>
     * <li>the supplied target {@link URI}</li>
     * <li>the supplied request timeout</li>
     * <li>{@code Content-Type: application/json}</li>
     * <li>a {@code POST} request body containing the supplied JSON text</li>
     * </ul>
     *
     * <p>
     * Callers may further customize the returned builder, for example by adding
     * authentication or provider-specific headers, before invoking
     * {@link HttpRequest.Builder#build()}.
     * </p>
     *
     * @param uri     target URI of the request
     * @param body    serialized JSON request body
     * @param timeout request timeout
     * @return preconfigured HTTP request builder for a JSON {@code POST} request
     */
    public HttpRequest.Builder jsonPost(URI uri, String body, Duration timeout) {
        return HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }
}