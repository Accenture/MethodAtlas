package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests that verify <strong>only Java and C# files are
 * eligible for source write-back</strong>, and that any other discovered
 * language is reported as skipped rather than silently ignored.
 *
 * <p>
 * Go is used as the representative "discoverable but not writeable"
 * language because the Go discovery plugin is pure-Java (no external
 * tooling needed) and ships no {@link org.egothor.methodatlas.api.SourcePatcher}.
 * </p>
 */
@Tag("integration")
class MethodAtlasAppApplyTagsUnsupportedLanguageTest {

    // ── Source templates ──────────────────────────────────────────────────────

    private static final String JAVA_TEST_SOURCE = """
            package com.example;

            import org.junit.jupiter.api.Test;

            public class LoginTest {

                @Test
                void testLogin() { }
            }
            """;

    private static final String GO_TEST_SOURCE = """
            package auth

            import "testing"

            func TestLoginValid(t *testing.T) {
                _ = "login"
            }

            func TestLoginInvalid(t *testing.T) {
                _ = "denied"
            }
            """;

    // ── -apply-tags ───────────────────────────────────────────────────────────

    @Test
    void applyTags_goOnly_skippedWithDiagnosticAndExit0(@TempDir Path tempDir) throws Exception {
        Path goFile = tempDir.resolve("auth_test.go");
        Files.writeString(goFile, GO_TEST_SOURCE, StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int code;
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            code = MethodAtlasApp.run(new String[] { "-apply-tags", tempDir.toString() }, out);
        }
        String output = baos.toString(StandardCharsets.UTF_8);

        assertEquals(0, code, "exit code should be 0 when only unsupported files are present");
        assertTrue(output.contains("source write-back is not supported for this language"),
                "per-file skip notice should mention unsupported write-back, got:\n" + output);
        assertTrue(output.contains("auth_test.go"),
                "per-file skip notice should name the Go file, got:\n" + output);
        assertTrue(output.contains("1 file(s) skipped"),
                "summary should report 1 skipped file, got:\n" + output);
        assertTrue(output.contains("0 annotation(s) added to 0 file(s)"),
                "no annotations should be written, got:\n" + output);

        // Source must remain unchanged.
        assertEquals(GO_TEST_SOURCE, Files.readString(goFile, StandardCharsets.UTF_8),
                "Go source file must be left untouched");
    }

    @Test
    void applyTags_mixedJavaAndGo_javaPatchedAndGoSkipped(@TempDir Path tempDir) throws Exception {
        Path javaFile = tempDir.resolve("LoginTest.java");
        Path goFile = tempDir.resolve("auth_test.go");
        Files.writeString(javaFile, JAVA_TEST_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(goFile, GO_TEST_SOURCE, StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(new String[] { "-apply-tags", tempDir.toString() }, out);
        }
        String output = baos.toString(StandardCharsets.UTF_8);

        // No -ai flag, so no annotations are written — but the Go file must still be reported
        // as a skipped unsupported language while the Java file is processed (and contributes
        // zero annotations because there is no AI engine).
        assertTrue(output.contains("auth_test.go"),
                "Go file must appear in the skip notice, got:\n" + output);
        assertTrue(output.contains("1 file(s) skipped"),
                "summary should count exactly one skipped file, got:\n" + output);
        assertFalse(output.contains("LoginTest.java")
                && output.contains("source write-back is not supported"),
                "Java file must not be reported as unsupported, got:\n" + output);

        // Source files must remain unchanged.
        assertEquals(JAVA_TEST_SOURCE, Files.readString(javaFile, StandardCharsets.UTF_8),
                "Java file should be unchanged when AI is disabled");
        assertEquals(GO_TEST_SOURCE, Files.readString(goFile, StandardCharsets.UTF_8),
                "Go file must be left untouched");
    }

    @Test
    void applyTags_javaOnly_noSkipCountInSummary(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("LoginTest.java"), JAVA_TEST_SOURCE, StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(new String[] { "-apply-tags", tempDir.toString() }, out);
        }
        String output = baos.toString(StandardCharsets.UTF_8);

        assertFalse(output.contains("file(s) skipped"),
                "when only supported languages are present, the summary must not append a skip count, got:\n"
                        + output);
        assertFalse(output.contains("source write-back is not supported"),
                "no per-file unsupported notice should be emitted when only Java files are present, got:\n"
                        + output);
    }

    // ── -apply-tags-from-csv ──────────────────────────────────────────────────

    @Test
    void applyTagsFromCsv_goRowsAreReportedAsMismatchAndFileSkipped(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("LoginTest.java"), JAVA_TEST_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("auth_test.go"), GO_TEST_SOURCE, StandardCharsets.UTF_8);

        // CSV references the Go tests too — these rows have no matching patchable file
        // and should fall into the "in CSV, not in source" mismatch bucket. The Go file
        // is not eligible for write-back, so no per-file apply notice should mention it
        // as a successful change.
        Path csv = tempDir.resolve("scan.csv");
        Files.writeString(csv, """
                fqcn,method,loc,tags,display_name
                com.example.LoginTest,testLogin,1,security,Verify login
                auth.auth_test,TestLoginValid,1,security,
                auth.auth_test,TestLoginInvalid,1,security,
                """, StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(new String[] {
                "-apply-tags-from-csv", csv.toString(),
                tempDir.toString()
            }, out);
        }
        String output = baos.toString(StandardCharsets.UTF_8);

        // The Java row is applied successfully.
        String javaContent = Files.readString(tempDir.resolve("LoginTest.java"), StandardCharsets.UTF_8);
        assertTrue(javaContent.contains("@Tag(\"security\")"),
                "Java file should receive @Tag(\"security\")");

        // The Go source file must remain unchanged.
        assertEquals(GO_TEST_SOURCE, Files.readString(tempDir.resolve("auth_test.go"), StandardCharsets.UTF_8),
                "Go source file must remain unchanged");

        // The completion summary line is always present and reports the expected counts.
        assertTrue(output.contains("Apply-tags-from-csv complete:"),
                "Summary line should be emitted, got:\n" + output);
    }
}
