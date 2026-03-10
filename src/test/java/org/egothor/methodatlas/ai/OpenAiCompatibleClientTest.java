package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenAiCompatibleClientTest {

    @Test
    void isAvailable_returnsTrueWhenApiKeyIsConfigured() {
        AiOptions options = AiOptions.builder().provider(AiProvider.OPENAI).apiKey("sk-test-value").build();

        OpenAiCompatibleClient client = new OpenAiCompatibleClient(options);

        assertTrue(client.isAvailable());
    }

    @Test
    void isAvailable_returnsFalseWhenApiKeyIsMissing() {
        AiOptions options = AiOptions.builder().provider(AiProvider.OPENAI).build();

        OpenAiCompatibleClient client = new OpenAiCompatibleClient(options);

        assertFalse(client.isAvailable());
    }

    @Test
    void suggestForClass_parsesWrappedJson_normalizesInvalidEntries_andBuildsExpectedRequestBody() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fqcn = "com.acme.security.AccessControlServiceTest";
        String classSource = """
                class AccessControlServiceTest {
                    void shouldRejectUnauthenticatedRequest() {}
                }
                """;
        String taxonomyText = "security, auth, access-control";
        List<PromptBuilder.TargetMethod> targetMethods = List
                .of(new PromptBuilder.TargetMethod("shouldAllowOwnerToReadOwnStatement", null, null),
                        new PromptBuilder.TargetMethod("shouldAllowAdministratorToReadAnyStatement", null, null),
                        new PromptBuilder.TargetMethod("shouldDenyForeignUserFromReadingAnotherUsersStatement", null,
                                null),
                        new PromptBuilder.TargetMethod("shouldRejectUnauthenticatedRequest", null, null),
                        new PromptBuilder.TargetMethod("shouldRenderFriendlyAccountLabel", null, null));

        String responseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "Here is the result:\\n{\\n  \\"className\\": \\"com.acme.security.AccessControlServiceTest\\",\\n  \\"classSecurityRelevant\\": true,\\n  \\"classTags\\": null,\\n  \\"classReason\\": \\"Class validates authentication and authorization controls.\\",\\n  \\"methods\\": [\\n    null,\\n    {\\n      \\"methodName\\": \\"shouldRejectUnauthenticatedRequest\\",\\n      \\"securityRelevant\\": true,\\n      \\"displayName\\": \\"SECURITY: authentication - reject unauthenticated request\\",\\n      \\"tags\\": null,\\n      \\"reason\\": \\"The test rejects anonymous access to a protected operation.\\"\\n    },\\n    {\\n      \\"methodName\\": \\"\\",\\n      \\"securityRelevant\\": true,\\n      \\"displayName\\": \\"SECURITY: invalid - blank method\\",\\n      \\"tags\\": [\\"security\\"],\\n      \\"reason\\": \\"This malformed method must be filtered.\\"\\n    }\\n  ]\\n}\\nThanks."
                      }
                    }
                  ]
                }
                """;

        AtomicReference<String> capturedBody = new AtomicReference<>();

        try (MockedConstruction<HttpSupport> mocked = mockHttpSupport(mapper, responseBody, capturedBody)) {
            AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENAI).modelName("gpt-4o-mini")
                    .baseUrl("https://api.openai.com").apiKey("sk-test-value").build();

            OpenAiCompatibleClient client = new OpenAiCompatibleClient(options);
            AiClassSuggestion suggestion = client.suggestForClass(fqcn, classSource, taxonomyText, targetMethods);

            assertEquals(fqcn, suggestion.className());
            assertEquals(Boolean.TRUE, suggestion.classSecurityRelevant());
            assertEquals(List.of(), suggestion.classTags());
            assertEquals("Class validates authentication and authorization controls.", suggestion.classReason());
            assertNotNull(suggestion.methods());
            assertEquals(1, suggestion.methods().size());

            AiMethodSuggestion method = suggestion.methods().get(0);
            assertEquals("shouldRejectUnauthenticatedRequest", method.methodName());
            assertTrue(method.securityRelevant());
            assertEquals("SECURITY: authentication - reject unauthenticated request", method.displayName());
            assertEquals(List.of(), method.tags());
            assertEquals("The test rejects anonymous access to a protected operation.", method.reason());

            HttpSupport httpSupport = mocked.constructed().get(0);
            verify(httpSupport).postJson(
                    argThat(request -> request.uri().toString().equals("https://api.openai.com/v1/chat/completions")
                            && "Bearer sk-test-value".equals(request.headers().firstValue("Authorization").orElse(null))
                            && "application/json".equals(request.headers().firstValue("Content-Type").orElse(null))
                            && "POST".equals(request.method())));

            String requestBody = capturedBody.get();
            assertNotNull(requestBody);
            assertTrue(requestBody.contains("\"model\":\"gpt-4o-mini\""));
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

    @Test
    void suggestForClass_addsOpenRouterHeaders() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String responseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"className\\":\\"com.acme.audit.AuditLoggingTest\\",\\"classSecurityRelevant\\":false,\\"classTags\\":[],\\"classReason\\":\\"Class is not security-relevant as a whole.\\",\\"methods\\":[]}"
                      }
                    }
                  ]
                }
                """;

        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null),
                new PromptBuilder.TargetMethod("shouldNotLogRawBearerToken", null, null),
                new PromptBuilder.TargetMethod("shouldNotLogPlaintextPasswordOnAuthenticationFailure", null, null),
                new PromptBuilder.TargetMethod("shouldFormatHumanReadableSupportMessage", null, null));

        try (MockedConstruction<HttpSupport> mocked = mockHttpSupport(mapper, responseBody, null)) {
            AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENROUTER)
                    .modelName("openai/gpt-4o-mini").baseUrl("https://openrouter.ai/api").apiKey("or-test-key").build();

            OpenAiCompatibleClient client = new OpenAiCompatibleClient(options);
            AiClassSuggestion suggestion = client.suggestForClass("com.acme.audit.AuditLoggingTest",
                    "class AuditLoggingTest {}", "security, logging", targetMethods);

            assertEquals("com.acme.audit.AuditLoggingTest", suggestion.className());

            HttpSupport httpSupport = mocked.constructed().get(0);
            verify(httpSupport).postJson(
                    argThat(request -> request.uri().toString().equals("https://openrouter.ai/api/v1/chat/completions")
                            && "Bearer or-test-key".equals(request.headers().firstValue("Authorization").orElse(null))
                            && "https://methodatlas.local"
                                    .equals(request.headers().firstValue("HTTP-Referer").orElse(null))
                            && "MethodAtlas".equals(request.headers().firstValue("X-Title").orElse(null))));
        }
    }

    @Test
    void suggestForClass_throwsWhenNoChoicesAreReturned() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fqcn = "com.acme.audit.AuditLoggingTest";
        String responseBody = """
                {
                  "choices": []
                }
                """;

        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null),
                new PromptBuilder.TargetMethod("shouldNotLogRawBearerToken", null, null),
                new PromptBuilder.TargetMethod("shouldNotLogPlaintextPasswordOnAuthenticationFailure", null, null),
                new PromptBuilder.TargetMethod("shouldFormatHumanReadableSupportMessage", null, null));

        try (MockedConstruction<HttpSupport> mocked = mockHttpSupport(mapper, responseBody, null)) {
            AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENAI).apiKey("sk-test-value")
                    .build();

            OpenAiCompatibleClient client = new OpenAiCompatibleClient(options);

            AiSuggestionException ex = org.junit.jupiter.api.Assertions.assertThrows(AiSuggestionException.class,
                    () -> client.suggestForClass(fqcn, "class AuditLoggingTest {}", "security, logging",
                            targetMethods));

            assertEquals("OpenAI-compatible suggestion failed for " + fqcn, ex.getMessage());
            assertInstanceOf(AiSuggestionException.class, ex.getCause());
            assertEquals("No choices returned by model", ex.getCause().getMessage());
        }
    }

    @Test
    void suggestForClass_throwsWhenModelReturnsTextWithoutJsonObject() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String fqcn = "com.acme.audit.AuditLoggingTest";
        String responseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "I think this class is probably security relevant, but I will not provide JSON."
                      }
                    }
                  ]
                }
                """;

        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null),
                new PromptBuilder.TargetMethod("shouldNotLogRawBearerToken", null, null),
                new PromptBuilder.TargetMethod("shouldNotLogPlaintextPasswordOnAuthenticationFailure", null, null),
                new PromptBuilder.TargetMethod("shouldFormatHumanReadableSupportMessage", null, null));

        try (MockedConstruction<HttpSupport> mocked = mockHttpSupport(mapper, responseBody, null)) {
            AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENAI).apiKey("sk-test-value")
                    .build();

            OpenAiCompatibleClient client = new OpenAiCompatibleClient(options);

            AiSuggestionException ex = org.junit.jupiter.api.Assertions.assertThrows(AiSuggestionException.class,
                    () -> client.suggestForClass(fqcn, "class AuditLoggingTest {}", "security, logging",
                            targetMethods));

            assertEquals("OpenAI-compatible suggestion failed for " + fqcn, ex.getMessage());
            assertInstanceOf(AiSuggestionException.class, ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("Model response does not contain a JSON object"));
        }
    }

    private static MockedConstruction<HttpSupport> mockHttpSupport(ObjectMapper mapper, String responseBody,
            AtomicReference<String> capturedBody) {

        return mockConstruction(HttpSupport.class, (mock, context) -> {
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