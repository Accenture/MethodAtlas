package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Unit tests for {@link HttpSupport}.
 *
 * <p>
 * This class verifies the retry-after resolution logic and the HTTP 429
 * retry loop behaviour of {@link HttpSupport#postJson}. The
 * {@link HttpSupport#resolveRetryAfter} method is tested directly to confirm
 * that it prefers the {@code Retry-After} header over the response body,
 * correctly falls back to the conservative default when the supplied wait time
 * is zero or absent, and always returns a positive value so that retries are
 * never sent immediately against a rate-limited provider.
 * </p>
 *
 * <p>
 * Integration tests for {@link HttpSupport#postJson} use a minimal in-process
 * HTTP server to avoid any dependency on external services.
 * </p>
 */
@Tag("unit")
@Tag("http-support")
class HttpSupportTest {

    // -------------------------------------------------------------------------
    // resolveRetryAfter – unit tests via package-private visibility
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resolveRetryAfter returns Retry-After header value when header is present and positive")
    @Tag("positive")
    void resolveRetryAfter_returnsHeaderValueWhenPositive() {
        HttpResponse<String> response = mockResponse(
                Map.of("Retry-After", List.of("42")), "{}");

        long result = HttpSupport.resolveRetryAfter(response);

        assertEquals(42L, result);
    }

    @Test
    @DisplayName("resolveRetryAfter falls back to DEFAULT_RETRY_WAIT_SECONDS when Retry-After header is '0'")
    @Tag("positive")
    void resolveRetryAfter_fallsBackToDefaultWhenHeaderIsZero() {
        HttpResponse<String> response = mockResponse(
                Map.of("Retry-After", List.of("0")), "{}");

        long result = HttpSupport.resolveRetryAfter(response);

        assertEquals(HttpSupport.DEFAULT_RETRY_WAIT_SECONDS, result,
                "A Retry-After value of 0 must not be used as-is; the default wait should apply");
    }

    @Test
    @DisplayName("resolveRetryAfter parses wait time from response body when Retry-After header is absent")
    @Tag("positive")
    void resolveRetryAfter_parsesBodyWhenHeaderAbsent() {
        HttpResponse<String> response = mockResponse(
                Map.of(), "{\"error\":{\"message\":\"Please wait 30 seconds before retrying.\"}}");

        long result = HttpSupport.resolveRetryAfter(response);

        assertEquals(30L, result);
    }

    @Test
    @DisplayName("resolveRetryAfter falls back to DEFAULT_RETRY_WAIT_SECONDS when body says 'Please wait 0 seconds'")
    @Tag("positive")
    void resolveRetryAfter_fallsBackToDefaultWhenBodySaysZeroSeconds() {
        HttpResponse<String> response = mockResponse(
                Map.of(),
                "{\"error\":{\"message\":\"Rate limit exceeded. Please wait 0 seconds before retrying.\"}}");

        long result = HttpSupport.resolveRetryAfter(response);

        assertEquals(HttpSupport.DEFAULT_RETRY_WAIT_SECONDS, result,
                "A body-parsed wait of 0 seconds must fall back to the default to avoid immediate retry");
    }

    @Test
    @DisplayName("resolveRetryAfter returns DEFAULT_RETRY_WAIT_SECONDS when neither header nor body contains a wait hint")
    @Tag("positive")
    void resolveRetryAfter_returnsDefaultWhenNoHintPresent() {
        HttpResponse<String> response = mockResponse(Map.of(), "{}");

        long result = HttpSupport.resolveRetryAfter(response);

        assertEquals(HttpSupport.DEFAULT_RETRY_WAIT_SECONDS, result);
    }

    @Test
    @DisplayName("resolveRetryAfter prefers Retry-After header over body-parsed value when both are present")
    @Tag("positive")
    void resolveRetryAfter_prefersHeaderOverBody() {
        HttpResponse<String> response = mockResponse(
                Map.of("Retry-After", List.of("15")),
                "{\"error\":{\"message\":\"Please wait 90 seconds before retrying.\"}}");

        long result = HttpSupport.resolveRetryAfter(response);

        assertEquals(15L, result, "Retry-After header must take precedence over body-parsed value");
    }

    // -------------------------------------------------------------------------
    // postJson – integration tests using an in-process HTTP server
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("postJson returns response body on HTTP 200")
    @Tag("positive")
    void postJson_returnsBodyOnSuccess() throws Exception {
        withServer(exchange -> {
            byte[] payload = "{\"ok\":true}".getBytes();
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }, serverUri -> {
            HttpSupport support = new HttpSupport(Duration.ofSeconds(5), 0);
            HttpRequest request = support
                    .jsonPost(serverUri, "{}", Duration.ofSeconds(5))
                    .build();

            String body = support.postJson(request);

            assertEquals("{\"ok\":true}", body);
        });
    }

    @Test
    @DisplayName("postJson retries once on HTTP 429 with Retry-After: 1 and returns response body on subsequent 200")
    @Tag("positive")
    void postJson_retriesOnce_afterRateLimit() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        withServer(exchange -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                byte[] payload = "rate limited".getBytes();
                exchange.sendResponseHeaders(429, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            } else {
                byte[] payload = "{\"ok\":true}".getBytes();
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            }
        }, serverUri -> {
            HttpSupport support = new HttpSupport(Duration.ofSeconds(5), 1);
            HttpRequest request = support
                    .jsonPost(serverUri, "{}", Duration.ofSeconds(5))
                    .build();

            String body = support.postJson(request);

            assertEquals("{\"ok\":true}", body);
            assertEquals(2, callCount.get(), "Expected exactly 2 HTTP calls: 1 rate-limited + 1 retry");
        });
    }

    @Test
    @DisplayName("postJson throws IOException after exhausting all retries on persistent HTTP 429")
    @Tag("negative")
    void postJson_throwsAfterExhaustingRetries() throws Exception {
        withServer(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "1");
            byte[] payload = "rate limited".getBytes();
            exchange.sendResponseHeaders(429, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }, serverUri -> {
            HttpSupport support = new HttpSupport(Duration.ofSeconds(5), 1);
            HttpRequest request = support
                    .jsonPost(serverUri, "{}", Duration.ofSeconds(5))
                    .build();

            assertThrows(IOException.class, () -> support.postJson(request));
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> mockResponse(Map<String, List<String>> headerMap, String body) {
        HttpHeaders httpHeaders = HttpHeaders.of(headerMap, (k, v) -> true);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(httpHeaders);
        when(response.body()).thenReturn(body);
        return response;
    }

    @FunctionalInterface
    private interface ServerHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    @FunctionalInterface
    private interface ServerConsumer {
        void accept(URI serverUri) throws Exception;
    }

    private static void withServer(ServerHandler handler, ServerConsumer consumer) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            consumer.accept(URI.create("http://localhost:" + port + "/"));
        } finally {
            server.stop(0);
        }
    }
}
