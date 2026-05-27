package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link OllamaClient}.
 *
 * <p>
 * This class verifies availability detection via the Ollama tags endpoint,
 * the happy-path JSON parsing and normalization of the suggest-for-class
 * response, and the error path when the model returns text without a JSON
 * object. {@link HttpSupport} is mocked via Mockito constructor mocking.
 * </p>
 */
@Tag("unit")
@Tag("ollama-client")
class OllamaClientTest {

    @Test
    @DisplayName("isAvailable returns true when the Ollama /api/tags endpoint responds successfully")
    @Tag("positive")
    void isAvailable_returnsTrueWhenTagsEndpointResponds() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> response = mock(HttpResponse.class);

        when(httpClient.send(any(HttpRequest.class), anyVoidBodyHandler())).thenReturn(response);

        try (MockedConstruction<HttpSupport> mocked = mockConstruction(HttpSupport.class, (mock, ctx) -> {
            when(mock.httpClient()).thenReturn(httpClient);
        })) {
            AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OLLAMA)
                    .baseUrl("http://localhost:11434").build();

            OllamaClient client = new OllamaClient(options);

            assertTrue(client.isAvailable());

            verify(httpClient)
                    .send(argThat(request -> request.uri().toString().equals("http://localhost:11434/api/tags")
                            && "GET".equals(request.method())), anyVoidBodyHandler());
        }
    }

    @Test
    @DisplayName("isAvailable returns false when the Ollama /api/tags endpoint throws an IOException")
    @Tag("negative")
    void isAvailable_returnsFalseWhenTagsEndpointFails() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);

        when(httpClient.send(any(HttpRequest.class), anyVoidBodyHandler()))
                .thenThrow(new java.io.IOException("Connection refused"));

        try (MockedConstruction<HttpSupport> mocked = mockConstruction(HttpSupport.class, (mock, ctx) -> {
            when(mock.httpClient()).thenReturn(httpClient);
        })) {
            AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OLLAMA)
                    .baseUrl("http://localhost:11434").build();

            OllamaClient client = new OllamaClient(options);

            assertFalse(client.isAvailable());
        }
    }

    @Test
    @DisplayName("suggestForClass parses wrapped JSON, normalizes invalid entries, and builds expected request body")
    @Tag("positive")
    void suggestForClass_parsesWrappedJson_normalizesInvalidEntries_andBuildsExpectedRequestBody() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fqcn = "com.acme.storage.PathTraversalValidationTest";
        String classSource = """
                class PathTraversalValidationTest {
                    void shouldRejectRelativePathTraversalSequence() {}
                }
                """;
        String taxonomyText = "security, input-validation, owasp";
        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldRejectRelativePathTraversalSequence", null, null),
                new PromptBuilder.TargetMethod("shouldRejectNestedTraversalAfterNormalization", null, null),
                new PromptBuilder.TargetMethod("shouldAllowSafePathInsideUploadRoot", null, null),
                new PromptBuilder.TargetMethod("shouldBuildDownloadFileName", null, null));

        String responseBody = """
                {
                  "message": {
                    "content": "Analysis complete:\\n{\\n  \\"className\\": \\"com.acme.storage.PathTraversalValidationTest\\",\\n  \\"classSecurityRelevant\\": true,\\n  \\"classTags\\": null,\\n  \\"classReason\\": \\"Class validates filesystem input handling.\\",\\n  \\"methods\\": [\\n    null,\\n    {\\n      \\"methodName\\": \\"shouldRejectRelativePathTraversalSequence\\",\\n      \\"securityRelevant\\": true,\\n      \\"displayName\\": \\"SECURITY: input validation - reject path traversal sequence\\",\\n      \\"tags\\": null,\\n      \\"reason\\": \\"The test rejects a classic parent-directory traversal payload.\\"\\n    },\\n    {\\n      \\"methodName\\": \\"   \\",\\n      \\"securityRelevant\\": true,\\n      \\"displayName\\": \\"SECURITY: invalid - blank method\\",\\n      \\"tags\\": [\\"security\\"],\\n      \\"reason\\": \\"This malformed method must be filtered.\\"\\n    }\\n  ]\\n}"
                  }
                }
                """;

        AtomicReference<String> capturedBody = new AtomicReference<>();

        try (MockedConstruction<HttpSupport> mocked = mockConstruction(HttpSupport.class, (mock, ctx) -> {
            when(mock.objectMapper()).thenReturn(mapper);
            when(mock.jsonPost(any(URI.class), any(String.class), any(Duration.class))).thenAnswer(invocation -> {
                URI uri = invocation.getArgument(0);
                String body = invocation.getArgument(1);
                Duration timeout = invocation.getArgument(2);

                capturedBody.set(body);

                return HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            });
            when(mock.postJson(any(HttpRequest.class))).thenReturn(responseBody);
        })) {
            AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OLLAMA)
                    .modelName("qwen2.5-coder:7b").baseUrl("http://localhost:11434").build();

            OllamaClient client = new OllamaClient(options);
            AiClassSuggestion suggestion = client.suggestForClass(fqcn, classSource, taxonomyText, targetMethods);

            assertEquals(fqcn, suggestion.className());
            assertEquals(Boolean.TRUE, suggestion.classSecurityRelevant());
            assertEquals(List.of(), suggestion.classTags());
            assertEquals("Class validates filesystem input handling.", suggestion.classReason());
            assertNotNull(suggestion.methods());
            assertEquals(1, suggestion.methods().size());

            AiMethodSuggestion method = suggestion.methods().get(0);
            assertEquals("shouldRejectRelativePathTraversalSequence", method.methodName());
            assertTrue(method.securityRelevant());
            assertEquals("SECURITY: input validation - reject path traversal sequence", method.displayName());
            assertEquals(List.of(), method.tags());
            assertEquals("The test rejects a classic parent-directory traversal payload.", method.reason());

            HttpSupport httpSupport = mocked.constructed().get(0);
            verify(httpSupport)
                    .postJson(argThat(request -> request.uri().toString().equals("http://localhost:11434/api/chat")
                            && "application/json".equals(request.headers().firstValue("Content-Type").orElse(null))
                            && "POST".equals(request.method())));

            String requestBody = capturedBody.get();
            assertNotNull(requestBody);
            assertTrue(requestBody.contains("\"model\":\"qwen2.5-coder:7b\""));
            assertTrue(requestBody.contains("\"stream\":false"));
            assertTrue(requestBody.contains("FQCN: " + fqcn));
            assertTrue(requestBody.contains("PathTraversalValidationTest"));
            assertTrue(requestBody.contains("shouldRejectRelativePathTraversalSequence"));
            assertTrue(requestBody.contains("shouldRejectNestedTraversalAfterNormalization"));
            assertTrue(requestBody.contains("shouldAllowSafePathInsideUploadRoot"));
            assertTrue(requestBody.contains("shouldBuildDownloadFileName"));
            assertTrue(requestBody.contains(taxonomyText));
        }
    }

    @Test
    @DisplayName("suggestForClass throws AiSuggestionException when model returns text without a JSON object")
    @Tag("negative")
    void suggestForClass_throwsWhenModelReturnsTextWithoutJsonObject() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fqcn = "com.acme.audit.AuditLoggingTest";
        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null),
                new PromptBuilder.TargetMethod("shouldNotLogRawBearerToken", null, null),
                new PromptBuilder.TargetMethod("shouldNotLogPlaintextPasswordOnAuthenticationFailure", null, null),
                new PromptBuilder.TargetMethod("shouldFormatHumanReadableSupportMessage", null, null));

        String responseBody = """
                {
                  "message": {
                    "content": "This looks security related, but I am not returning JSON."
                  }
                }
                """;

        try (MockedConstruction<HttpSupport> mocked = mockConstruction(HttpSupport.class, (mock, ctx) -> {
            when(mock.objectMapper()).thenReturn(mapper);
            when(mock.jsonPost(any(URI.class), any(String.class), any(Duration.class))).thenAnswer(invocation -> {
                URI uri = invocation.getArgument(0);
                String body = invocation.getArgument(1);
                Duration timeout = invocation.getArgument(2);
                return HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            });
            when(mock.postJson(any(HttpRequest.class))).thenReturn(responseBody);
        })) {
            AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OLLAMA).build();

            OllamaClient client = new OllamaClient(options);

            AiSuggestionException ex = org.junit.jupiter.api.Assertions.assertThrows(AiSuggestionException.class,
                    () -> client.suggestForClass(fqcn, "class AuditLoggingTest {}", "security, logging",
                            targetMethods));

            assertEquals("Ollama suggestion failed for " + fqcn, ex.getMessage());
            assertInstanceOf(AiSuggestionException.class, ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("Model response does not contain a JSON object"));
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<Void> anyVoidBodyHandler() {
        return any(HttpResponse.BodyHandler.class);
    }
}
