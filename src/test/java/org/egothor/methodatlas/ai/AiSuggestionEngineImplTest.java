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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class AiSuggestionEngineImplTest {

    @TempDir
    Path tempDir;

    @Test
    void suggestForClass_delegatesToProviderClient_usingDefaultTaxonomy() throws Exception {
        AiProviderClient client = mock(AiProviderClient.class);
        AiClassSuggestion expected = new AiClassSuggestion("com.acme.security.AccessControlServiceTest", true,
                List.of("security", "access-control"), "Class validates access-control behavior.",
                List.of(new AiMethodSuggestion("shouldRejectUnauthenticatedRequest", true,
                        "SECURITY: authentication - reject unauthenticated request", List.of("security", "auth"),
                        "The test verifies that anonymous access is rejected.")));

        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENAI).build();

        try (MockedStatic<AiProviderFactory> factory = mockStatic(AiProviderFactory.class)) {
            factory.when(() -> AiProviderFactory.create(options)).thenReturn(client);
            when(client.suggestForClass(eq("com.acme.security.AccessControlServiceTest"),
                    eq("class AccessControlServiceTest {}"), eq(DefaultSecurityTaxonomy.text()))).thenReturn(expected);

            AiSuggestionEngineImpl engine = new AiSuggestionEngineImpl(options);
            AiClassSuggestion actual = engine.suggestForClass("com.acme.security.AccessControlServiceTest",
                    "class AccessControlServiceTest {}");

            assertSame(expected, actual);

            factory.verify(() -> AiProviderFactory.create(options));
            verify(client).suggestForClass("com.acme.security.AccessControlServiceTest",
                    "class AccessControlServiceTest {}", DefaultSecurityTaxonomy.text());
            verifyNoMoreInteractions(client);
        }
    }

    @Test
    void suggestForClass_delegatesToProviderClient_usingOptimizedTaxonomy() throws Exception {
        AiProviderClient client = mock(AiProviderClient.class);
        AiClassSuggestion expected = new AiClassSuggestion("com.acme.storage.PathTraversalValidationTest", true,
                List.of("security", "input-validation"), "Class validates protection against unsafe path input.",
                List.of(new AiMethodSuggestion("shouldRejectRelativePathTraversalSequence", true,
                        "SECURITY: input validation - reject path traversal sequence",
                        List.of("security", "input-validation", "owasp"),
                        "The test rejects a classic path traversal payload.")));

        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OLLAMA)
                .taxonomyMode(AiOptions.TaxonomyMode.OPTIMIZED).build();

        try (MockedStatic<AiProviderFactory> factory = mockStatic(AiProviderFactory.class)) {
            factory.when(() -> AiProviderFactory.create(options)).thenReturn(client);
            when(client.suggestForClass(eq("com.acme.storage.PathTraversalValidationTest"),
                    eq("class PathTraversalValidationTest {}"), eq(OptimizedSecurityTaxonomy.text())))
                    .thenReturn(expected);

            AiSuggestionEngineImpl engine = new AiSuggestionEngineImpl(options);
            AiClassSuggestion actual = engine.suggestForClass("com.acme.storage.PathTraversalValidationTest",
                    "class PathTraversalValidationTest {}");

            assertSame(expected, actual);

            factory.verify(() -> AiProviderFactory.create(options));
            verify(client).suggestForClass("com.acme.storage.PathTraversalValidationTest",
                    "class PathTraversalValidationTest {}", OptimizedSecurityTaxonomy.text());
            verifyNoMoreInteractions(client);
        }
    }

    @Test
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
                        "The test ensures credentials are not written to logs.")));

        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENROUTER).taxonomyFile(taxonomyFile)
                .build();

        try (MockedStatic<AiProviderFactory> factory = mockStatic(AiProviderFactory.class)) {
            factory.when(() -> AiProviderFactory.create(options)).thenReturn(client);
            when(client.suggestForClass(eq("com.acme.audit.AuditLoggingTest"), eq("class AuditLoggingTest {}"),
                    eq(taxonomyText))).thenReturn(expected);

            AiSuggestionEngineImpl engine = new AiSuggestionEngineImpl(options);
            AiClassSuggestion actual = engine.suggestForClass("com.acme.audit.AuditLoggingTest",
                    "class AuditLoggingTest {}");

            assertSame(expected, actual);

            factory.verify(() -> AiProviderFactory.create(options));
            verify(client).suggestForClass("com.acme.audit.AuditLoggingTest", "class AuditLoggingTest {}",
                    taxonomyText);
            verifyNoMoreInteractions(client);
        }
    }

    @Test
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