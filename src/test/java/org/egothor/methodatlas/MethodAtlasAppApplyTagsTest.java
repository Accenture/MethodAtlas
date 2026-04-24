package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

/**
 * Integration tests for the {@code -apply-tags} mode.
 */
class MethodAtlasAppApplyTagsTest {

    // -------------------------------------------------------------------------
    // Test source templates
    // -------------------------------------------------------------------------

    private static final String LOGIN_TEST_SOURCE = """
            package com.example;

            import org.junit.jupiter.api.Test;

            public class LoginTest {

                @Test
                void testLoginWithValidCredentials() { }

                @Test
                void testCountItems() { }
            }
            """;

    // -------------------------------------------------------------------------
    // Happy-path: AI suggestions applied to source files
    // -------------------------------------------------------------------------

    @Test
    void applyTags_withAi_annotatesSecurityRelevantMethod(@TempDir Path tempDir) throws Exception {
        Path src = writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);

        AiClassSuggestion suggestion = buildSuggestion(
                new AiMethodSuggestion("testLoginWithValidCredentials", true,
                        "Verifies login with valid credentials is accepted",
                        List.of("security", "auth"), null, 0.95, 0.0),
                new AiMethodSuggestion("testCountItems", false, null, List.of(), null, 0.0, 0.0));

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> when(mock.suggestForClass(anyString(), eq("com.example.LoginTest"), anyString(), any()))
                        .thenReturn(suggestion))) {

            runApp(new String[] { "-ai", "-apply-tags", tempDir.toString() });
        }

        String modified = Files.readString(src, StandardCharsets.UTF_8);
        assertTrue(modified.contains("@DisplayName(\"Verifies login with valid credentials is accepted\")"),
                "Expected @DisplayName on security-relevant method");
        assertTrue(modified.contains("@Tag(\"security\")"), "Expected @Tag(\"security\")");
        assertTrue(modified.contains("@Tag(\"auth\")"), "Expected @Tag(\"auth\")");
    }

    @Test
    void applyTags_nonSecurityMethod_notAnnotated(@TempDir Path tempDir) throws Exception {
        Path src = writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);

        AiClassSuggestion suggestion = buildSuggestion(
                new AiMethodSuggestion("testLoginWithValidCredentials", false, "irrelevant",
                        List.of(), null, 0.0, 0.0),
                new AiMethodSuggestion("testCountItems", false, null, List.of(), null, 0.0, 0.0));

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> when(mock.suggestForClass(anyString(), anyString(), anyString(), any()))
                        .thenReturn(suggestion))) {

            runApp(new String[] { "-ai", "-apply-tags", tempDir.toString() });
        }

        String content = Files.readString(src, StandardCharsets.UTF_8);
        assertFalse(content.contains("@DisplayName"), "No @DisplayName should be added for non-security methods");
        assertFalse(content.contains("@Tag(\"security\")"), "No security tags should be added");
    }

    @Test
    void applyTags_existingAnnotationsNotDuplicated(@TempDir Path tempDir) throws Exception {
        String alreadyAnnotated = """
                package com.example;

                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Tag;
                import org.junit.jupiter.api.Test;

                public class LoginTest {

                    @Test
                    @DisplayName("existing name")
                    @Tag("security")
                    void testLoginWithValidCredentials() { }
                }
                """;
        Path src = writeSource(tempDir, "LoginTest.java", alreadyAnnotated);

        AiClassSuggestion suggestion = buildSuggestion(
                new AiMethodSuggestion("testLoginWithValidCredentials", true,
                        "new AI name", List.of("security"), null, 0.9, 0.0));

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> when(mock.suggestForClass(anyString(), eq("com.example.LoginTest"), anyString(), any()))
                        .thenReturn(suggestion))) {

            runApp(new String[] { "-ai", "-apply-tags", tempDir.toString() });
        }

        String content = Files.readString(src, StandardCharsets.UTF_8);
        assertFalse(content.contains("new AI name"), "Existing @DisplayName should not be replaced");
        // "security" tag appears exactly once (not duplicated)
        assertEquals(1, countOccurrences(content, "@Tag(\"security\")"), "Tag should not be duplicated");
    }

    @Test
    void applyTags_noAi_noFilesModified(@TempDir Path tempDir) throws Exception {
        Path src = writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        String originalContent = Files.readString(src, StandardCharsets.UTF_8);

        // -apply-tags without -ai: AI engine is null, nothing should be annotated
        runApp(new String[] { "-apply-tags", tempDir.toString() });

        assertEquals(originalContent, Files.readString(src, StandardCharsets.UTF_8),
                "File should be unchanged when AI is disabled");
    }

    // -------------------------------------------------------------------------
    // Summary output
    // -------------------------------------------------------------------------

    @Test
    void applyTags_summaryLineAlwaysEmitted(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);

        String output = runApp(new String[] { "-apply-tags", tempDir.toString() });

        assertTrue(output.contains("Apply-tags complete:"), "Summary line should be present");
    }

    @Test
    void applyTags_summaryCountsMatchAnnotationsAdded(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);

        AiClassSuggestion suggestion = buildSuggestion(
                new AiMethodSuggestion("testLoginWithValidCredentials", true,
                        "Verifies login", List.of("security", "auth"), null, 0.9, 0.0),
                new AiMethodSuggestion("testCountItems", false, null, List.of(), null, 0.0, 0.0));

        String output;
        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> when(mock.suggestForClass(anyString(), eq("com.example.LoginTest"), anyString(), any()))
                        .thenReturn(suggestion))) {

            output = runApp(new String[] { "-ai", "-apply-tags", tempDir.toString() });
        }

        // 1 @DisplayName + 2 @Tag ("security", "auth") = 3 annotations in 1 file
        assertTrue(output.contains("3 annotation(s) added to 1 file"), output);
    }

    @Test
    void applyTags_exitCode0WhenNoErrors(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            int code = MethodAtlasApp.run(new String[] { "-apply-tags", tempDir.toString() }, out);
            assertEquals(0, code);
        }
    }

    // -------------------------------------------------------------------------
    // Import management
    // -------------------------------------------------------------------------

    @Test
    void applyTags_addsDisplayNameImport(@TempDir Path tempDir) throws Exception {
        Path src = writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);

        AiClassSuggestion suggestion = buildSuggestion(
                new AiMethodSuggestion("testLoginWithValidCredentials", true,
                        "Security display name", List.of(), null, 0.9, 0.0));

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> when(mock.suggestForClass(anyString(), eq("com.example.LoginTest"), anyString(), any()))
                        .thenReturn(suggestion))) {

            runApp(new String[] { "-ai", "-apply-tags", tempDir.toString() });
        }

        String content = Files.readString(src, StandardCharsets.UTF_8);
        assertTrue(content.contains("import org.junit.jupiter.api.DisplayName;"), content);
    }

    @Test
    void applyTags_addsTagImport(@TempDir Path tempDir) throws Exception {
        Path src = writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);

        AiClassSuggestion suggestion = buildSuggestion(
                new AiMethodSuggestion("testLoginWithValidCredentials", true,
                        null, List.of("security"), null, 0.9, 0.0));

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> when(mock.suggestForClass(anyString(), eq("com.example.LoginTest"), anyString(), any()))
                        .thenReturn(suggestion))) {

            runApp(new String[] { "-ai", "-apply-tags", tempDir.toString() });
        }

        String content = Files.readString(src, StandardCharsets.UTF_8);
        assertTrue(content.contains("import org.junit.jupiter.api.Tag;"), content);
    }

    @Test
    void applyTags_noImportsAddedWhenNothingAnnotated(@TempDir Path tempDir) throws Exception {
        Path src = writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);

        // AI marks everything as non-security → nothing annotated → no imports
        AiClassSuggestion suggestion = buildSuggestion(
                new AiMethodSuggestion("testLoginWithValidCredentials", false, null, List.of(), null, 0.0, 0.0),
                new AiMethodSuggestion("testCountItems", false, null, List.of(), null, 0.0, 0.0));

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> when(mock.suggestForClass(anyString(), anyString(), anyString(), any()))
                        .thenReturn(suggestion))) {

            runApp(new String[] { "-ai", "-apply-tags", tempDir.toString() });
        }

        String content = Files.readString(src, StandardCharsets.UTF_8);
        assertFalse(content.contains("import org.junit.jupiter.api.DisplayName;"), content);
        assertFalse(content.contains("import org.junit.jupiter.api.Tag;"), content);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Path writeSource(Path dir, String filename, String source) throws IOException {
        Path file = dir.resolve(filename);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private static AiClassSuggestion buildSuggestion(AiMethodSuggestion... methods) {
        return new AiClassSuggestion(null, null, null, null, List.of(methods));
    }

    private static String runApp(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(args, out);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }
}
