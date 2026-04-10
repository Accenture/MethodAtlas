package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpRequest;
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
 * Unit tests for {@link AnthropicClient}.
 *
 * <p>
 * This class verifies availability detection based on the configured API key,
 * successful JSON parsing and normalization of a messages API response,
 * correct Anthropic-specific request headers ({@code x-api-key} and
 * {@code anthropic-version}), and error handling for null or empty content
 * arrays, absent text blocks, and responses that contain no JSON object.
 * {@link HttpSupport} is mocked via Mockito constructor mocking so that no
 * real HTTP traffic is produced.
 * </p>
 */
@Tag("unit")
@Tag("anthropic-client")
class AnthropicClientTest {

    // -------------------------------------------------------------------------
    // isAvailable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isAvailable returns true when an API key is configured")
    @Tag("positive")
    void isAvailable_returnsTrueWhenApiKeyIsConfigured() {
        AiOptions options = AiOptions.builder().provider(AiProvider.ANTHROPIC).apiKey("sk-ant-test").build();

        AnthropicClient client = new AnthropicClient(options);

        assertTrue(client.isAvailable());
    }

    @Test
    @DisplayName("isAvailable returns false when no API key is configured")
    @Tag("negative")
    void isAvailable_returnsFalseWhenApiKeyIsMissing() {
        AiOptions options = AiOptions.builder().provider(AiProvider.ANTHROPIC).build();

        AnthropicClient client = new AnthropicClient(options);

        assertFalse(client.isAvailable());
    }

    // -------------------------------------------------------------------------
    // suggestForClass – happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("suggestForClass parses wrapped JSON, normalizes invalid entries, and builds correct request")
    @Tag("positive")
    void suggestForClass_parsesWrappedJson_normalizesInvalidEntries_andBuildsExpectedRequest() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fqcn = "com.acme.security.AccessControlServiceTest";
        String classSource = """
                class AccessControlServiceTest {
                    void shouldRejectUnauthenticatedRequest() {}
                }
                """;
        String taxonomyText = "security, auth, access-control";
        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldAllowOwnerToReadOwnStatement", null, null),
                new PromptBuilder.TargetMethod("shouldAllowAdministratorToReadAnyStatement", null, null),
                new PromptBuilder.TargetMethod("shouldDenyForeignUserFromReadingAnotherUsersStatement", null, null),
                new PromptBuilder.TargetMethod("shouldRejectUnauthenticatedRequest", null, null),
                new PromptBuilder.TargetMethod("shouldRenderFriendlyAccountLabel", null, null));

        // The response body deliberately contains:
        //   - a "thinking" block (non-text) that must be skipped by the type filter
        //   - a text block whose text starts with prose before the JSON object (exercising
        //     JsonText.extractFirstJsonObject)
        //   - a null entry in the methods array  (exercises null-filter in normalize())
        //   - a method with a blank methodName   (exercises blank-filter in normalize())
        //   - a valid method with tags: null     (exercises null-tags replacement in normalize())
        //   - classTags: null                    (exercises null-classTags replacement in normalize())
        String responseBody = """
                {
                  "content": [
                    {
                      "type": "thinking",
                      "thinking": "Let me analyze the class carefully."
                    },
                    {
                      "type": "text",
                      "text": "Here is the result:\\n{\\n  \\"className\\": \\"com.acme.security.AccessControlServiceTest\\",\\n  \\"classSecurityRelevant\\": true,\\n  \\"classTags\\": null,\\n  \\"classReason\\": \\"Class validates authentication and authorization controls.\\",\\n  \\"methods\\": [\\n    null,\\n    {\\n      \\"methodName\\": \\"shouldRejectUnauthenticatedRequest\\",\\n      \\"securityRelevant\\": true,\\n      \\"displayName\\": \\"SECURITY: authentication - reject unauthenticated request\\",\\n      \\"tags\\": null,\\n      \\"reason\\": \\"The test rejects anonymous access to a protected operation.\\"\\n    },\\n    {\\n      \\"methodName\\": \\"\\",\\n      \\"securityRelevant\\": true,\\n      \\"displayName\\": \\"SECURITY: invalid - blank method\\",\\n      \\"tags\\": [\\"security\\"],\\n      \\"reason\\": \\"This malformed method must be filtered.\\"\\n    }\\n  ]\\n}\\nThanks."
                    }
                  ]
                }
                """;

        AtomicReference<String> capturedBody = new AtomicReference<>();

        try (MockedConstruction<HttpSupport> mocked = mockHttpSupport(mapper, responseBody, capturedBody)) {
            AiOptions options = AiOptions.builder()
                    .enabled(true)
                    .provider(AiProvider.ANTHROPIC)
                    .modelName("claude-3-5-sonnet-20241022")
                    .baseUrl("https://api.anthropic.com")
                    .apiKey("sk-ant-test-value")
                    .build();

            AnthropicClient client = new AnthropicClient(options);
            AiClassSuggestion suggestion = client.suggestForClass(fqcn, classSource, taxonomyText, targetMethods);

            // Verify the normalized suggestion fields.
            assertEquals(fqcn, suggestion.className());
            assertEquals(Boolean.TRUE, suggestion.classSecurityRelevant());
            assertEquals(List.of(), suggestion.classTags());   // null → List.of() via normalize()
            assertEquals("Class validates authentication and authorization controls.", suggestion.classReason());
            assertNotNull(suggestion.methods());
            assertEquals(1, suggestion.methods().size());      // null entry + blank name both removed

            AiMethodSuggestion method = suggestion.methods().get(0);
            assertEquals("shouldRejectUnauthenticatedRequest", method.methodName());
            assertTrue(method.securityRelevant());
            assertEquals("SECURITY: authentication - reject unauthenticated request", method.displayName());
            assertEquals(List.of(), method.tags());            // null tags → List.of() via normalize()
            assertEquals("The test rejects anonymous access to a protected operation.", method.reason());

            // Verify the HTTP request targets the Anthropic messages endpoint with the
            // two Anthropic-specific headers.
            HttpSupport httpSupport = mocked.constructed().get(0);
            verify(httpSupport).postJson(argThat(request ->
                    request.uri().toString().equals("https://api.anthropic.com/v1/messages")
                    && "sk-ant-test-value".equals(request.headers().firstValue("x-api-key").orElse(null))
                    && "2023-06-01".equals(request.headers().firstValue("anthropic-version").orElse(null))
                    && "application/json".equals(request.headers().firstValue("Content-Type").orElse(null))
                    && "POST".equals(request.method())));

            // Verify the serialized request body carries all expected fields.
            String requestBody = capturedBody.get();
            assertNotNull(requestBody);
            assertTrue(requestBody.contains("\"model\":\"claude-3-5-sonnet-20241022\""));
            assertTrue(requestBody.contains("FQCN: " + fqcn));
            assertTrue(requestBody.contains("AccessControlServiceTest"));
            assertTrue(requestBody.contains("shouldAllowOwnerToReadOwnStatement"));
            assertTrue(requestBody.contains("shouldAllowAdministratorToReadAnyStatement"));
            assertTrue(requestBody.contains("shouldDenyForeignUserFromReadingAnotherUsersStatement"));
            assertTrue(requestBody.contains("shouldRejectUnauthenticatedRequest"));
            assertTrue(requestBody.contains("shouldRenderFriendlyAccountLabel"));
            assertTrue(requestBody.contains(taxonomyText));
            assertTrue(requestBody.contains("\"temperature\":0.0"));
        }
    }

    // -------------------------------------------------------------------------
    // suggestForClass – error paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("suggestForClass throws AiSuggestionException with 'No content returned by Anthropic' when content list is empty")
    @Tag("negative")
    void suggestForClass_throwsWhenContentListIsEmpty() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fqcn = "com.acme.audit.AuditLoggingTest";
        String responseBody = """
                {
                  "content": []
                }
                """;

        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null));

        try (MockedConstruction<HttpSupport> mocked = mockHttpSupport(mapper, responseBody, null)) {
            AiOptions options = AiOptions.builder()
                    .enabled(true).provider(AiProvider.ANTHROPIC).apiKey("sk-ant-test").build();

            AnthropicClient client = new AnthropicClient(options);

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> client.suggestForClass(fqcn, "class AuditLoggingTest {}", "security, logging",
                            targetMethods));

            assertEquals("Anthropic suggestion failed for " + fqcn, ex.getMessage());
            assertInstanceOf(AiSuggestionException.class, ex.getCause());
            assertEquals("No content returned by Anthropic", ex.getCause().getMessage());
        }
    }

    @Test
    @DisplayName("suggestForClass throws AiSuggestionException with 'No content returned by Anthropic' when content field is absent from the response")
    @Tag("edge-case")
    void suggestForClass_throwsWhenContentFieldIsAbsentFromResponse() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fqcn = "com.acme.audit.AuditLoggingTest";
        // Empty JSON object: Jackson leaves MessageResponse.content as null.
        String responseBody = "{}";

        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null));

        try (MockedConstruction<HttpSupport> mocked = mockHttpSupport(mapper, responseBody, null)) {
            AiOptions options = AiOptions.builder()
                    .enabled(true).provider(AiProvider.ANTHROPIC).apiKey("sk-ant-test").build();

            AnthropicClient client = new AnthropicClient(options);

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> client.suggestForClass(fqcn, "class AuditLoggingTest {}", "security, logging",
                            targetMethods));

            assertEquals("Anthropic suggestion failed for " + fqcn, ex.getMessage());
            assertInstanceOf(AiSuggestionException.class, ex.getCause());
            assertEquals("No content returned by Anthropic", ex.getCause().getMessage());
        }
    }

    @Test
    @DisplayName("suggestForClass throws AiSuggestionException with 'Anthropic returned no text block' when only non-text blocks are present")
    @Tag("negative")
    void suggestForClass_throwsWhenNoTextBlockIsPresent() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fqcn = "com.acme.audit.AuditLoggingTest";
        // Content has a "thinking" block only – the text-type filter finds nothing.
        String responseBody = """
                {
                  "content": [
                    {
                      "type": "thinking",
                      "thinking": "I should return JSON but I am confused."
                    }
                  ]
                }
                """;

        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null));

        try (MockedConstruction<HttpSupport> mocked = mockHttpSupport(mapper, responseBody, null)) {
            AiOptions options = AiOptions.builder()
                    .enabled(true).provider(AiProvider.ANTHROPIC).apiKey("sk-ant-test").build();

            AnthropicClient client = new AnthropicClient(options);

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> client.suggestForClass(fqcn, "class AuditLoggingTest {}", "security, logging",
                            targetMethods));

            assertEquals("Anthropic suggestion failed for " + fqcn, ex.getMessage());
            assertInstanceOf(AiSuggestionException.class, ex.getCause());
            assertEquals("Anthropic returned no text block", ex.getCause().getMessage());
        }
    }

    @Test
    @DisplayName("suggestForClass throws AiSuggestionException when text block contains no JSON object")
    @Tag("negative")
    void suggestForClass_throwsWhenTextBlockContainsNoJsonObject() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fqcn = "com.acme.audit.AuditLoggingTest";
        String responseBody = """
                {
                  "content": [
                    {
                      "type": "text",
                      "text": "I think this class is probably security relevant, but I will not provide JSON."
                    }
                  ]
                }
                """;

        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null));

        try (MockedConstruction<HttpSupport> mocked = mockHttpSupport(mapper, responseBody, null)) {
            AiOptions options = AiOptions.builder()
                    .enabled(true).provider(AiProvider.ANTHROPIC).apiKey("sk-ant-test").build();

            AnthropicClient client = new AnthropicClient(options);

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> client.suggestForClass(fqcn, "class AuditLoggingTest {}", "security, logging",
                            targetMethods));

            assertEquals("Anthropic suggestion failed for " + fqcn, ex.getMessage());
            assertInstanceOf(AiSuggestionException.class, ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("Model response does not contain a JSON object"),
                    ex.getCause().getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MockedConstruction<HttpSupport> mockHttpSupport(ObjectMapper mapper, String responseBody,
            AtomicReference<String> capturedBody) {

        return mockConstruction(HttpSupport.class, (mock, _) -> {
            when(mock.objectMapper()).thenReturn(mapper);
            when(mock.jsonPost(any(URI.class), any(String.class), any(Duration.class))).thenAnswer(invocation -> {
                URI uri = invocation.getArgument(0);
                String body = invocation.getArgument(1);
                Duration timeout = invocation.getArgument(2);

                if (capturedBody != null) {
                    capturedBody.set(body);
                }

                return HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            });
            when(mock.postJson(any(HttpRequest.class))).thenReturn(responseBody);
        });
    }
}
