package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for the {@code -apply-tags-from-csv} mode.
 *
 * <p>
 * Each test writes a minimal Java source file and a matching CSV to a temp
 * directory, invokes {@link MethodAtlasApp#run}, and then asserts on the
 * resulting source file content and/or the return code.
 * </p>
 */
@Tag("integration")
class MethodAtlasAppApplyTagsFromCsvTest {

    // ── Source templates ──────────────────────────────────────────────────────

    private static final String LOGIN_TEST_SOURCE = """
            package com.example;

            import org.junit.jupiter.api.Test;

            public class LoginTest {

                @Test
                void testLogin() { }

                @Test
                void testLogout() { }
            }
            """;

    private static final String TAGGED_TEST_SOURCE = """
            package com.example;

            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Tag;
            import org.junit.jupiter.api.Test;

            public class LoginTest {

                @Test
                @DisplayName("Login with valid credentials")
                @Tag("security")
                @Tag("auth")
                void testLogin() { }

                @Test
                void testLogout() { }
            }
            """;

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void applyTagsFromCsv_addsMissingTags(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,security;auth,Verify login
                com.example.LoginTest,testLogout,1,,
                """);

        runApp(tempDir, "scan.csv");

        String content = readSource(tempDir, "LoginTest.java");
        assertTrue(content.contains("@Tag(\"security\")"), "Expected @Tag(\"security\")");
        assertTrue(content.contains("@Tag(\"auth\")"), "Expected @Tag(\"auth\")");
        assertTrue(content.contains("@DisplayName(\"Verify login\")"), "Expected @DisplayName");
    }

    @Test
    void applyTagsFromCsv_removesTagsNotInCsv(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", TAGGED_TEST_SOURCE);
        // CSV requests only "security" — "auth" should be removed, DisplayName cleared
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,security,
                com.example.LoginTest,testLogout,1,,
                """);

        runApp(tempDir, "scan.csv");

        String content = readSource(tempDir, "LoginTest.java");
        assertTrue(content.contains("@Tag(\"security\")"), "security tag should remain");
        assertFalse(content.contains("@Tag(\"auth\")"), "auth tag should be removed");
        assertFalse(content.contains("@DisplayName"), "DisplayName should be removed when CSV is empty");
    }

    @Test
    void applyTagsFromCsv_removesDisplayNameWhenCsvEmpty(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", TAGGED_TEST_SOURCE);
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,,
                com.example.LoginTest,testLogout,1,,
                """);

        runApp(tempDir, "scan.csv");

        String content = readSource(tempDir, "LoginTest.java");
        assertFalse(content.contains("@DisplayName"), "DisplayName should be removed");
        assertFalse(content.contains("@Tag("), "All tags should be removed");
    }

    @Test
    void applyTagsFromCsv_setsDisplayNameFromCsv(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,,Login verifies session creation
                com.example.LoginTest,testLogout,1,,
                """);

        runApp(tempDir, "scan.csv");

        String content = readSource(tempDir, "LoginTest.java");
        assertTrue(content.contains("@DisplayName(\"Login verifies session creation\")"), content);
    }

    @Test
    void applyTagsFromCsv_addsTagImportWhenTagsAdded(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,security,
                com.example.LoginTest,testLogout,1,,
                """);

        runApp(tempDir, "scan.csv");

        String content = readSource(tempDir, "LoginTest.java");
        assertTrue(content.contains("import org.junit.jupiter.api.Tag;"), content);
    }

    @Test
    void applyTagsFromCsv_addsDisplayNameImportWhenDisplayNameSet(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,,Some display name
                com.example.LoginTest,testLogout,1,,
                """);

        runApp(tempDir, "scan.csv");

        String content = readSource(tempDir, "LoginTest.java");
        assertTrue(content.contains("import org.junit.jupiter.api.DisplayName;"), content);
    }

    @Test
    void applyTagsFromCsv_noChangesWhenAlreadyMatchesCsv(@TempDir Path tempDir) throws Exception {
        // Source has @Tag("security") and no DisplayName; CSV says same thing
        String source = """
                package com.example;

                import org.junit.jupiter.api.Tag;
                import org.junit.jupiter.api.Test;

                public class LoginTest {

                    @Test
                    @Tag("security")
                    void testLogin() { }

                    @Test
                    void testLogout() { }
                }
                """;
        writeSource(tempDir, "LoginTest.java", source);
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,security,
                com.example.LoginTest,testLogout,1,,
                """);

        String output = runApp(tempDir, "scan.csv");

        // No "Modified:" line expected — summary should say 0 changes in 0 file(s)
        assertTrue(output.contains("0 change(s) in 0 file(s)"), output);
    }

    @Test
    void applyTagsFromCsv_returnsZeroOnSuccess(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,,
                com.example.LoginTest,testLogout,1,,
                """);

        int code = runAppReturnCode(tempDir, "scan.csv");
        assertEquals(0, code);
    }

    @Test
    void applyTagsFromCsv_summarisesChangesInOutput(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,security,Verify login
                com.example.LoginTest,testLogout,1,,
                """);

        String output = runApp(tempDir, "scan.csv");

        assertTrue(output.contains("Apply-tags-from-csv complete:"), output);
        assertTrue(output.contains("change(s)"), output);
    }

    // ── Mismatch handling ─────────────────────────────────────────────────────

    @Test
    void applyTagsFromCsv_mismatchLimitAborts(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        // CSV has a method not present in source → 1 mismatch; limit = 1 → abort
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,,
                com.example.LoginTest,testLogout,1,,
                com.example.LoginTest,nonExistentMethod,1,,
                """);

        String originalContent = readSource(tempDir, "LoginTest.java");
        String output = runApp(tempDir, "scan.csv", "-mismatch-limit", "1");

        assertEquals(originalContent, readSource(tempDir, "LoginTest.java"),
                "Source file must not be modified when mismatch limit is exceeded");
        assertTrue(output.contains("aborted"), output);
        assertTrue(output.contains("MISMATCH"), output);
    }

    @Test
    void applyTagsFromCsv_mismatchLimitReturnsOne(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,,
                com.example.LoginTest,testLogout,1,,
                com.example.LoginTest,ghostMethod,1,,
                """);

        int code = runAppReturnCode(tempDir, "scan.csv", "-mismatch-limit", "1");
        assertEquals(1, code, "Exit code must be 1 when mismatch limit is exceeded");
    }

    @Test
    void applyTagsFromCsv_mismatchBelowLimitProceeds(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        // 1 mismatch, limit = 2 → proceed
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,security,
                com.example.LoginTest,testLogout,1,,
                com.example.LoginTest,ghost,1,,
                """);

        runApp(tempDir, "scan.csv", "-mismatch-limit", "2");

        String content = readSource(tempDir, "LoginTest.java");
        assertTrue(content.contains("@Tag(\"security\")"),
                "Changes should be applied when mismatch count is below limit");
    }

    @Test
    void applyTagsFromCsv_noLimitWarnsAndProceeds(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,security,
                com.example.LoginTest,testLogout,1,,
                com.example.LoginTest,ghost,1,,
                """);

        // Default -mismatch-limit is -1 (no abort)
        int code = runApp(tempDir, "scan.csv", new String[0]);

        assertEquals(0, code);
        String content = readSource(tempDir, "LoginTest.java");
        assertTrue(content.contains("@Tag(\"security\")"),
                "Tags should be applied even when there are mismatches and no limit is set");
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void applyTagsFromCsv_emptyCsvReturnsZero(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        writeCsv(tempDir, "scan.csv", "fqcn,method,loc,tags,display_name\n");

        String output = runApp(tempDir, "scan.csv");
        assertTrue(output.contains("no records"), output);
    }

    @Test
    void applyTagsFromCsv_methodInSourceNotInCsvCountedAsMismatch(@TempDir Path tempDir) throws Exception {
        writeSource(tempDir, "LoginTest.java", LOGIN_TEST_SOURCE);
        // Only one of the two methods in source is listed → testLogout is a mismatch
        writeCsv(tempDir, "scan.csv", """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,security,
                """);

        String output = runApp(tempDir, "scan.csv", "-mismatch-limit", "1");

        // One mismatch (testLogout in source, not in CSV) → abort
        assertTrue(output.contains("aborted"), output);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void writeSource(Path dir, String filename, String source) throws IOException {
        Files.writeString(dir.resolve(filename), source, StandardCharsets.UTF_8);
    }

    private static void writeCsv(Path dir, String filename, String content) throws IOException {
        Files.writeString(dir.resolve(filename), content, StandardCharsets.UTF_8);
    }

    private static String readSource(Path dir, String filename) throws IOException {
        return Files.readString(dir.resolve(filename), StandardCharsets.UTF_8);
    }

    /** Runs the app and returns the captured output. Extra args are appended after the standard flags. */
    private static String runApp(Path tempDir, String csvName, String... extraArgs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            String[] baseArgs = new String[] {
                "-apply-tags-from-csv", tempDir.resolve(csvName).toString(),
                tempDir.toString()
            };
            String[] combined = concat(baseArgs, extraArgs);
            MethodAtlasApp.run(combined, out);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    /** Runs the app and returns the exit code. Extra args are appended after the standard flags. */
    private static int runAppReturnCode(Path tempDir, String csvName, String... extraArgs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            String[] baseArgs = new String[] {
                "-apply-tags-from-csv", tempDir.resolve(csvName).toString(),
                tempDir.toString()
            };
            String[] combined = concat(baseArgs, extraArgs);
            return MethodAtlasApp.run(combined, out);
        }
    }

    /** Overload that accepts a pre-built extra-args array (avoids varargs ambiguity). */
    private static int runApp(Path tempDir, String csvName, String[] extraArgs) throws Exception {
        return runAppReturnCode(tempDir, csvName, extraArgs);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] result = new String[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
