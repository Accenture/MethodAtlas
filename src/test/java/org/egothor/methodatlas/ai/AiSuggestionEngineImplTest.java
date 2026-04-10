package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link AiSuggestionEngineImpl}.
 *
 * <p>
 * This class verifies that the engine delegates to the correct provider client
 * with the correct taxonomy text (default, optimized, or external file),
 * propagates provider factory failures, and throws an appropriate
 * {@link AiSuggestionException} when a configured taxonomy file cannot be read.
 * </p>
 */
@Tag("unit")
@Tag("ai-suggestion-engine")
class AiSuggestionEngineImplTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("suggestForClass delegates to provider client using DefaultSecurityTaxonomy text")
    @Tag("positive")
    void suggestForClass_delegatesToProviderClient_usingDefaultTaxonomy() throws Exception {
        AiProviderClient client = mock(AiProviderClient.class);
        AiClassSuggestion expected = new AiClassSuggestion("com.acme.security.AccessControlServiceTest", true,
                List.of("security", "access-control"), "Class validates access-control behavior.",
                List.of(new AiMethodSuggestion("shouldRejectUnauthenticatedRequest", true,
                        "SECURITY: authentication - reject unauthenticated request", List.of("security", "auth"),
                        "The test verifies that anonymous access is rejected.", 0.0)));

        List<PromptBuilder.TargetMethod> targetMethods = List
                .of(new PromptBuilder.TargetMethod("shouldAllowOwnerToReadOwnStatement", null, null),
                        new PromptBuilder.TargetMethod("shouldAllowAdministratorToReadAnyStatement", null, null),
                        new PromptBuilder.TargetMethod("shouldDenyForeignUserFromReadingAnotherUsersStatement", null,
                                null),
                        new PromptBuilder.TargetMethod("shouldRejectUnauthenticatedRequest", null, null),
                        new PromptBuilder.TargetMethod("shouldRenderFriendlyAccountLabel", null, null));

        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENAI).build();

        try (MockedStatic<AiProviderFactory> factory = mockStatic(AiProviderFactory.class)) {
            factory.when(() -> AiProviderFactory.create(options)).thenReturn(client);
            when(client.suggestForClass(eq("com.acme.security.AccessControlServiceTest"),
                    eq("class AccessControlServiceTest {}"), eq(DefaultSecurityTaxonomy.text()), eq(targetMethods)))
                    .thenReturn(expected);

            AiSuggestionEngineImpl engine = new AiSuggestionEngineImpl(options);
            AiClassSuggestion actual = engine.suggestForClass("com.acme.security.AccessControlServiceTest",
                    "com.acme.security.AccessControlServiceTest", "class AccessControlServiceTest {}", targetMethods);

            assertSame(expected, actual);

            factory.verify(() -> AiProviderFactory.create(options));
            verify(client).suggestForClass("com.acme.security.AccessControlServiceTest",
                    "class AccessControlServiceTest {}", DefaultSecurityTaxonomy.text(), targetMethods);
            verifyNoMoreInteractions(client);
        }
    }

    @Test
    @DisplayName("suggestForClass delegates to provider client using OptimizedSecurityTaxonomy text")
    @Tag("positive")
    void suggestForClass_delegatesToProviderClient_usingOptimizedTaxonomy() throws Exception {
        AiProviderClient client = mock(AiProviderClient.class);
        AiClassSuggestion expected = new AiClassSuggestion("com.acme.storage.PathTraversalValidationTest", true,
                List.of("security", "input-validation"), "Class validates protection against unsafe path input.",
                List.of(new AiMethodSuggestion("shouldRejectRelativePathTraversalSequence", true,
                        "SECURITY: input validation - reject path traversal sequence",
                        List.of("security", "input-validation", "owasp"),
                        "The test rejects a classic path traversal payload.", 0.0)));

        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldRejectRelativePathTraversalSequence", null, null),
                new PromptBuilder.TargetMethod("shouldRejectNestedTraversalAfterNormalization", null, null),
                new PromptBuilder.TargetMethod("shouldAllowSafePathInsideUploadRoot", null, null),
                new PromptBuilder.TargetMethod("shouldBuildDownloadFileName", null, null));

        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OLLAMA)
                .taxonomyMode(AiOptions.TaxonomyMode.OPTIMIZED).build();

        try (MockedStatic<AiProviderFactory> factory = mockStatic(AiProviderFactory.class)) {
            factory.when(() -> AiProviderFactory.create(options)).thenReturn(client);
            when(client.suggestForClass(eq("com.acme.storage.PathTraversalValidationTest"),
                    eq("class PathTraversalValidationTest {}"), eq(OptimizedSecurityTaxonomy.text()),
                    eq(targetMethods))).thenReturn(expected);

            AiSuggestionEngineImpl engine = new AiSuggestionEngineImpl(options);
            AiClassSuggestion actual = engine.suggestForClass("com.acme.storage.PathTraversalValidationTest",
                    "com.acme.storage.PathTraversalValidationTest", "class PathTraversalValidationTest {}",
                    targetMethods);

            assertSame(expected, actual);

            factory.verify(() -> AiProviderFactory.create(options));
            verify(client).suggestForClass("com.acme.storage.PathTraversalValidationTest",
                    "class PathTraversalValidationTest {}", OptimizedSecurityTaxonomy.text(), targetMethods);
            verifyNoMoreInteractions(client);
        }
    }

    @Test
    @DisplayName("suggestForClass uses external taxonomy file content when taxonomyFile is configured")
    @Tag("positive")
    void suggestForClass_usesExternalTaxonomyFile_whenConfigured() throws Exception {
        Path taxonomyFile = tempDir.resolve("security-taxonomy.txt");
        String taxonomyText = """
                CUSTOM SECURITY TAXONOMY
                security
                access-control
                logging
                """;
        Files.writeString(taxonomyFile, taxonomyText);

        AiProviderClient client = mock(AiProviderClient.class);
        AiClassSuggestion expected = new AiClassSuggestion("com.acme.audit.AuditLoggingTest", true,
                List.of("security", "logging"), "Class verifies security-relevant audit logging behavior.",
                List.of(new AiMethodSuggestion("shouldNotLogRawBearerToken", true,
                        "SECURITY: logging - redact bearer token", List.of("security", "logging"),
                        "The test ensures credentials are not written to logs.", 0.0)));

        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldWriteAuditEventForPrivilegeChange", null, null),
                new PromptBuilder.TargetMethod("shouldNotLogRawBearerToken", null, null),
                new PromptBuilder.TargetMethod("shouldNotLogPlaintextPasswordOnAuthenticationFailure", null, null),
                new PromptBuilder.TargetMethod("shouldFormatHumanReadableSupportMessage", null, null));

        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENROUTER).taxonomyFile(taxonomyFile)
                .build();

        try (MockedStatic<AiProviderFactory> factory = mockStatic(AiProviderFactory.class)) {
            factory.when(() -> AiProviderFactory.create(options)).thenReturn(client);
            when(client.suggestForClass(eq("com.acme.audit.AuditLoggingTest"), eq("class AuditLoggingTest {}"),
                    eq(taxonomyText), eq(targetMethods))).thenReturn(expected);

            AiSuggestionEngineImpl engine = new AiSuggestionEngineImpl(options);
            AiClassSuggestion actual = engine.suggestForClass("com.acme.audit.AuditLoggingTest",
                    "com.acme.audit.AuditLoggingTest", "class AuditLoggingTest {}", targetMethods);

            assertSame(expected, actual);

            factory.verify(() -> AiProviderFactory.create(options));
            verify(client).suggestForClass("com.acme.audit.AuditLoggingTest", "class AuditLoggingTest {}", taxonomyText,
                    targetMethods);
            verifyNoMoreInteractions(client);
        }
    }

    @Test
    @DisplayName("constructor throws AiSuggestionException with expected message when taxonomy file cannot be read")
    @Tag("negative")
    void constructor_throwsWhenTaxonomyFileCannotBeRead() {
        Path missingTaxonomyFile = tempDir.resolve("missing-taxonomy.txt");

        AiProviderClient client = mock(AiProviderClient.class);
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.ANTHROPIC)
                .taxonomyFile(missingTaxonomyFile).build();

        try (MockedStatic<AiProviderFactory> factory = mockStatic(AiProviderFactory.class)) {
            factory.when(() -> AiProviderFactory.create(options)).thenReturn(client);

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> new AiSuggestionEngineImpl(options));

            assertEquals("Failed to read taxonomy file: " + missingTaxonomyFile, ex.getMessage());
            assertInstanceOf(IOException.class, ex.getCause());

            factory.verify(() -> AiProviderFactory.create(options));
            verifyNoMoreInteractions(client);
        }
    }

    @Test
    @DisplayName("constructor propagates AiSuggestionException thrown by AiProviderFactory.create")
    @Tag("negative")
    void constructor_propagatesProviderFactoryFailure() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENAI).build();

        AiSuggestionException expected = new AiSuggestionException("Provider initialization failed");

        try (MockedStatic<AiProviderFactory> factory = mockStatic(AiProviderFactory.class)) {
            factory.when(() -> AiProviderFactory.create(options)).thenThrow(expected);

            AiSuggestionException actual = assertThrows(AiSuggestionException.class,
                    () -> new AiSuggestionEngineImpl(options));

            assertSame(expected, actual);
        }
    }
}
