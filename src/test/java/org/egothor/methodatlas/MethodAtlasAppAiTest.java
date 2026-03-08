package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.egothor.methodatlas.ai.AiSuggestionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

class MethodAtlasAppAiTest {

    @Test
    void csvMode_aiEnabled_withRealisticFixtures_emitsMergedAiSuggestions(@TempDir Path tempDir) throws Exception {
        copyAllFixtures(tempDir);

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, context) -> {
                    when(mock.suggestForClass(eq("com.acme.tests.SampleOneTest"), anyString()))
                            .thenReturn(sampleOneSuggestion());
                    when(mock.suggestForClass(eq("com.acme.other.AnotherTest"), anyString()))
                            .thenReturn(anotherSuggestion());
                    when(mock.suggestForClass(eq("com.acme.security.AccessControlServiceTest"), anyString()))
                            .thenReturn(accessControlSuggestion());
                    when(mock.suggestForClass(eq("com.acme.storage.PathTraversalValidationTest"), anyString()))
                            .thenReturn(pathTraversalSuggestion());
                    when(mock.suggestForClass(eq("com.acme.audit.AuditLoggingTest"), anyString()))
                            .thenReturn(auditLoggingSuggestion());
                })) {

            String output = runAppCapturingStdout(new String[] { "-ai", tempDir.toString() });
            List<String> lines = nonEmptyLines(output);

            assertEquals(18, lines.size(), "Expected header + 17 method rows across 5 fixtures");
            assertEquals("fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason", lines.get(0));

            Map<String, List<String>> rows = parseCsvAiRows(lines);

            assertAiCsvRow(rows, "com.acme.security.AccessControlServiceTest", "shouldRejectUnauthenticatedRequest",
                    "security;authn", "true", "SECURITY: authentication - reject unauthenticated request",
                    "security;auth;access-control",
                    "The test verifies that anonymous access to a protected operation is rejected.");

            assertAiCsvRow(rows, "com.acme.storage.PathTraversalValidationTest",
                    "shouldRejectRelativePathTraversalSequence", "security;validation", "true",
                    "SECURITY: input validation - reject path traversal sequence", "security;input-validation;owasp",
                    "The test rejects a classic parent-directory traversal payload.");

            assertAiCsvRow(rows, "com.acme.audit.AuditLoggingTest", "shouldNotLogRawBearerToken", "security;logging",
                    "true", "SECURITY: logging - redact bearer token", "security;logging",
                    "The test ensures that sensitive bearer tokens are redacted before logging.");

            assertAiCsvRow(rows, "com.acme.audit.AuditLoggingTest", "shouldFormatHumanReadableSupportMessage", "",
                    "false", "", "", "The test is functional formatting coverage and is not security-specific.");

            assertAiCsvRow(rows, "com.acme.tests.SampleOneTest", "alpha", "fast;crypto", "true",
                    "SECURITY: crypto - validates encrypted happy path", "security;crypto",
                    "The test exercises a crypto-related security property.");

            assertAiCsvRow(rows, "com.acme.tests.SampleOneTest", "beta", "param", "", "", "", "");

            assertAiCsvRow(rows, "com.acme.other.AnotherTest", "delta", "", "false", "", "",
                    "The repeated test is not security-specific.");

            assertFalse(rows.containsKey("com.acme.tests.SampleOneTest#ghostMethod"),
                    "AI-only invented methods must not appear in CLI output");

            assertEquals(1, mocked.constructed().size(), "Expected one AI engine instance");
        }
    }

    @Test
    void plainMode_aiFailureForOneClass_continuesScanningAndFallsBackForThatClass(@TempDir Path tempDir)
            throws Exception {
        copyAllFixtures(tempDir);

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, context) -> {
                    when(mock.suggestForClass(eq("com.acme.tests.SampleOneTest"), anyString()))
                            .thenReturn(sampleOneSuggestion());
                    when(mock.suggestForClass(eq("com.acme.other.AnotherTest"), anyString()))
                            .thenReturn(anotherSuggestion());
                    when(mock.suggestForClass(eq("com.acme.security.AccessControlServiceTest"), anyString()))
                            .thenThrow(new AiSuggestionException("Simulated provider failure"));
                    when(mock.suggestForClass(eq("com.acme.storage.PathTraversalValidationTest"), anyString()))
                            .thenReturn(pathTraversalSuggestion());
                    when(mock.suggestForClass(eq("com.acme.audit.AuditLoggingTest"), anyString()))
                            .thenReturn(auditLoggingSuggestion());
                })) {

            String output = runAppCapturingStdout(new String[] { "-plain", "-ai", tempDir.toString() });
            List<String> lines = nonEmptyLines(output);

            assertEquals(17, lines.size(), "Expected one plain output line per discovered test method");

            String failedClassLine = findLineContaining(lines,
                    "com.acme.security.AccessControlServiceTest, shouldRejectUnauthenticatedRequest,");
            assertTrue(failedClassLine.contains("AI_SECURITY=-"));
            assertTrue(failedClassLine.contains("AI_DISPLAY=-"));
            assertTrue(failedClassLine.contains("AI_TAGS=-"));
            assertTrue(failedClassLine.contains("AI_REASON=-"));

            String unaffectedLine = findLineContaining(lines,
                    "com.acme.storage.PathTraversalValidationTest, shouldRejectRelativePathTraversalSequence,");
            assertTrue(unaffectedLine.contains("AI_SECURITY=true"));
            assertTrue(
                    unaffectedLine.contains("AI_DISPLAY=SECURITY: input validation - reject path traversal sequence"));
            assertTrue(unaffectedLine.contains("AI_TAGS=security;input-validation;owasp"));

            String nonSecurityLine = findLineContaining(lines,
                    "com.acme.audit.AuditLoggingTest, shouldFormatHumanReadableSupportMessage,");
            assertTrue(nonSecurityLine.contains("AI_SECURITY=false"));
            assertTrue(nonSecurityLine.contains("AI_DISPLAY=-"));
            assertTrue(nonSecurityLine.contains("AI_TAGS=-"));
            assertTrue(nonSecurityLine
                    .contains("AI_REASON=The test is functional formatting coverage and is not security-specific."));

            assertEquals(1, mocked.constructed().size(), "Expected one AI engine instance");
        }
    }

    @Test
    void csvMode_oversizedClass_skipsAiLookup_andLeavesAiColumnsEmpty(@TempDir Path tempDir) throws Exception {
        writeOversizedFixture(tempDir);

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class)) {
            String output = runAppCapturingStdout(
                    new String[] { "-ai", "-ai-max-class-chars", "10", tempDir.toString() });

            List<String> lines = nonEmptyLines(output);

            assertEquals(2, lines.size(), "Expected header + 1 emitted method row");
            assertEquals("fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason", lines.get(0));

            Map<String, List<String>> rows = parseCsvAiRows(lines);
            List<String> row = rows.get("com.acme.big.HugeAiSkipTest#hugeSecurityTest");

            assertNotNull(row, "Missing oversized-class row");
            assertEquals(8, row.size());
            assertEquals("security", row.get(3));
            assertEquals("", row.get(4));
            assertEquals("", row.get(5));
            assertEquals("", row.get(6));
            assertEquals("", row.get(7));

            assertEquals(1, mocked.constructed().size(), "Expected one AI engine instance");
            verify(mocked.constructed().get(0), never()).suggestForClass(anyString(), anyString());
        }
    }

    private static AiClassSuggestion sampleOneSuggestion() {
        return new AiClassSuggestion("com.acme.tests.SampleOneTest", true, List.of("security", "crypto"),
                "Class contains crypto-related security coverage.",
                List.of(new AiMethodSuggestion("alpha", true, "SECURITY: crypto - validates encrypted happy path",
                        List.of("security", "crypto"), "The test exercises a crypto-related security property."),
                        new AiMethodSuggestion("ghostMethod", true, "SECURITY: invented - should never appear",
                                List.of("security"), "This invented method must not be emitted by the CLI.")));
    }

    private static AiClassSuggestion anotherSuggestion() {
        return new AiClassSuggestion("com.acme.other.AnotherTest", false, List.of(), "Class is not security-relevant.",
                List.of(new AiMethodSuggestion("delta", false, null, List.of(),
                        "The repeated test is not security-specific.")));
    }

    private static AiClassSuggestion accessControlSuggestion() {
        return new AiClassSuggestion("com.acme.security.AccessControlServiceTest", true,
                List.of("security", "access-control"), "Class verifies authorization and authentication controls.",
                List.of(new AiMethodSuggestion("shouldAllowOwnerToReadOwnStatement", true,
                        "SECURITY: access control - allow owner access", List.of("security", "access-control"),
                        "The test verifies that the resource owner is granted access."),
                        new AiMethodSuggestion("shouldAllowAdministratorToReadAnyStatement", true,
                                "SECURITY: access control - allow administrator access",
                                List.of("security", "access-control"),
                                "The test verifies privileged administrative access."),
                        new AiMethodSuggestion("shouldDenyForeignUserFromReadingAnotherUsersStatement", true,
                                "SECURITY: access control - deny foreign user access",
                                List.of("security", "access-control"),
                                "The test verifies that one user cannot access another user's statement."),
                        new AiMethodSuggestion("shouldRejectUnauthenticatedRequest", true,
                                "SECURITY: authentication - reject unauthenticated request",
                                List.of("security", "auth", "access-control"),
                                "The test verifies that anonymous access to a protected operation is rejected."),
                        new AiMethodSuggestion("shouldRenderFriendlyAccountLabel", false, null, List.of(),
                                "The test is purely presentational and not security-specific.")));
    }

    private static AiClassSuggestion pathTraversalSuggestion() {
        return new AiClassSuggestion("com.acme.storage.PathTraversalValidationTest", true,
                List.of("security", "input-validation"), "Class validates filesystem input handling.",
                List.of(new AiMethodSuggestion("shouldRejectRelativePathTraversalSequence", true,
                        "SECURITY: input validation - reject path traversal sequence",
                        List.of("security", "input-validation", "owasp"),
                        "The test rejects a classic parent-directory traversal payload."),
                        new AiMethodSuggestion("shouldRejectNestedTraversalAfterNormalization", true,
                                "SECURITY: input validation - block normalized root escape",
                                List.of("security", "input-validation", "owasp"),
                                "The test verifies that normalized traversal cannot escape the upload root."),
                        new AiMethodSuggestion("shouldAllowSafePathInsideUploadRoot", true,
                                "SECURITY: input validation - allow safe normalized path",
                                List.of("security", "input-validation"),
                                "The test verifies that a normalized in-root path is accepted."),
                        new AiMethodSuggestion("shouldBuildDownloadFileName", false, null, List.of(),
                                "The test only formats a filename and is not security-specific.")));
    }

    private static AiClassSuggestion auditLoggingSuggestion() {
        return new AiClassSuggestion("com.acme.audit.AuditLoggingTest", true, List.of("security", "logging"),
                "Class verifies security-relevant logging and audit behavior.",
                List.of(new AiMethodSuggestion("shouldWriteAuditEventForPrivilegeChange", true,
                        "SECURITY: logging - audit privilege change", List.of("security", "logging"),
                        "The test verifies audit logging of a privileged security action."),
                        new AiMethodSuggestion("shouldNotLogRawBearerToken", true,
                                "SECURITY: logging - redact bearer token", List.of("security", "logging"),
                                "The test ensures that sensitive bearer tokens are redacted before logging."),
                        new AiMethodSuggestion("shouldNotLogPlaintextPasswordOnAuthenticationFailure", true,
                                "SECURITY: logging - avoid plaintext password disclosure",
                                List.of("security", "logging"),
                                "The test verifies that plaintext passwords are not written to logs."),
                        new AiMethodSuggestion("shouldFormatHumanReadableSupportMessage", false, null, List.of(),
                                "The test is functional formatting coverage and is not security-specific.")));
    }

    private static List<String> parseCsvFields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inQuotes = false;
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);

            if (inQuotes) {
                if (ch == '\"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                        current.append('\"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                current.append(ch);
                i++;
                continue;
            }

            if (ch == '\"') {
                inQuotes = true;
                i++;
                continue;
            }

            if (ch == ',') {
                out.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }

            current.append(ch);
            i++;
        }

        out.add(current.toString());
        return out;
    }

    private static void copyAllFixtures(Path tempDir) throws IOException {
        copyFixtures(tempDir, "SampleOneTest.java", "AnotherTest.java", "AccessControlServiceTest.java",
                "PathTraversalValidationTest.java", "AuditLoggingTest.java");
    }

    private static void copyFixtures(Path tempDir, String... fixtureFileNames) throws IOException {
        for (String fixtureFileName : fixtureFileNames) {
            copyFixture(tempDir, fixtureFileName);
        }
    }

    private static void copyFixture(Path destDir, String fixtureFileName) throws IOException {
        String resourcePath = "/fixtures/" + fixtureFileName + ".txt";
        try (InputStream in = MethodAtlasAppAiTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing test resource: " + resourcePath);
            Files.copy(in, destDir.resolve(fixtureFileName));
        }
    }

    private static String runAppCapturingStdout(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream previous = System.out;

        try (PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8)) {
            System.setOut(ps);
            MethodAtlasApp.main(args);
        } finally {
            System.setOut(previous);
        }

        return baos.toString(StandardCharsets.UTF_8);
    }

    private static List<String> nonEmptyLines(String text) {
        String[] parts = text.split("\\R");
        List<String> lines = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static String findLineContaining(List<String> lines, String fragment) {
        for (String line : lines) {
            if (line.contains(fragment)) {
                return line;
            }
        }
        throw new AssertionError("Missing line containing: " + fragment);
    }

    private static void writeOversizedFixture(Path tempDir) throws IOException {
        StringBuilder repeated = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            repeated.append("        String s").append(i).append(" = \"padding").append(i).append("\";\n");
        }

        String source = """
                package com.acme.big;

                import org.junit.jupiter.api.Tag;
                import org.junit.jupiter.api.Test;

                class HugeAiSkipTest {

                    @Test
                    @Tag("security")
                    void hugeSecurityTest() {
                """ + repeated + """
                    }
                }
                """;

        Files.writeString(tempDir.resolve("HugeAiSkipTest.java"), source, StandardCharsets.UTF_8);
    }

    private static void assertAiCsvRow(Map<String, List<String>> rows, String fqcn, String method,
            String expectedTagsText, String expectedAiSecurityRelevant, String expectedAiDisplayName,
            String expectedAiTagsText, String expectedAiReason) {

        List<String> fields = rows.get(fqcn + "#" + method);
        assertNotNull(fields, "Missing row for " + fqcn + "#" + method);

        assertEquals(8, fields.size(), "Expected 8 CSV fields for " + fqcn + "#" + method);
        assertEquals(expectedTagsText, fields.get(3), "Source tags mismatch for " + fqcn + "#" + method);
        assertEquals(expectedAiSecurityRelevant, fields.get(4), "AI security flag mismatch for " + fqcn + "#" + method);
        assertEquals(expectedAiDisplayName, fields.get(5), "AI display name mismatch for " + fqcn + "#" + method);
        assertEquals(expectedAiTagsText, fields.get(6), "AI tags mismatch for " + fqcn + "#" + method);
        assertEquals(expectedAiReason, fields.get(7), "AI reason mismatch for " + fqcn + "#" + method);
    }

    private static Map<String, List<String>> parseCsvAiRows(List<String> lines) {
        Map<String, List<String>> rows = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> fields = parseCsvFields(lines.get(i));
            assertEquals(8, fields.size(), "Expected 8 CSV fields, got " + fields.size() + " from: " + lines.get(i));
            rows.put(fields.get(0) + "#" + fields.get(1), fields);
        }
        return rows;
    }
}