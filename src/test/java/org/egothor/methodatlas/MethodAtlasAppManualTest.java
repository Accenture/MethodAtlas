package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for the manual AI workflow phases of {@link MethodAtlasApp}.
 *
 * <p>
 * These tests exercise the two-phase manual AI process:
 * </p>
 * <ul>
 * <li><b>Prepare phase</b> ({@code -manual-prepare}): verifies that work files
 * are created for discovered test classes and that each file contains valid
 * operator instructions and an AI prompt.</li>
 * <li><b>Consume phase</b> ({@code -manual-consume}): verifies that the
 * application reads pre-created response files, merges AI columns into the
 * final CSV, and emits blank AI columns for classes whose response file is
 * absent.</li>
 * </ul>
 */
public class MethodAtlasAppManualTest {

    // FQCNs of the classes declared in the standard fixture files
    private static final String FQCN_ACCESS_CONTROL = "com.acme.security.AccessControlServiceTest";
    private static final String FQCN_AUDIT = "com.acme.audit.AuditLoggingTest";

    // -------------------------------------------------------------------------
    // Prepare phase
    // -------------------------------------------------------------------------

    @Test
    void manualPrepare_createsWorkFilesForAllDiscoveredTestClasses(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(sourceDir);

        copyFixture(sourceDir, "AccessControlServiceTest.java");
        copyFixture(sourceDir, "AuditLoggingTest.java");

        String output = runAppCapturingStdout(
                new String[] { "-manual-prepare", workDir.toString(), sourceDir.toString() });

        // Work files are named <fqcn>.txt
        assertTrue(Files.exists(workDir.resolve(FQCN_ACCESS_CONTROL + ".txt")),
                "Work file for AccessControlServiceTest should exist");
        assertTrue(Files.exists(workDir.resolve(FQCN_AUDIT + ".txt")),
                "Work file for AuditLoggingTest should exist");

        // Progress lines printed to stdout
        assertTrue(output.contains("Prepared:"), "Output should contain progress lines");
        assertTrue(output.contains("Manual prepare complete"), "Output should contain completion message");
    }

    @Test
    void manualPrepare_workFileContainsOperatorInstructionsAndPrompt(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(sourceDir);

        copyFixture(sourceDir, "AccessControlServiceTest.java");

        runAppCapturingStdout(
                new String[] { "-manual-prepare", workDir.toString(), sourceDir.toString() });

        Path workFile = workDir.resolve(FQCN_ACCESS_CONTROL + ".txt");
        assertTrue(Files.exists(workFile));

        String content = Files.readString(workFile, StandardCharsets.UTF_8);

        // Operator instructions
        assertTrue(content.contains("OPERATOR INSTRUCTIONS"));
        assertTrue(content.contains(FQCN_ACCESS_CONTROL + ".response.txt"));
        assertTrue(content.contains("-manual-consume"));

        // AI prompt markers
        assertTrue(content.contains("--- BEGIN AI PROMPT ---"));
        assertTrue(content.contains("--- END AI PROMPT ---"));

        // Prompt contains class-specific content
        assertTrue(content.contains("FQCN: " + FQCN_ACCESS_CONTROL));
        assertTrue(content.contains("shouldAllowOwnerToReadOwnStatement"));
    }

    @Test
    void manualPrepare_producesNoCSVOutput(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(sourceDir);

        copyFixture(sourceDir, "AccessControlServiceTest.java");

        String output = runAppCapturingStdout(
                new String[] { "-manual-prepare", workDir.toString(), sourceDir.toString() });

        // Should not contain CSV header
        assertFalse(output.contains("fqcn,method,loc,tags"), "Prepare phase must not emit CSV header");
    }

    @Test
    void manualPrepare_returnsZeroExitCodeOnSuccess(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(sourceDir);

        copyFixture(sourceDir, "AccessControlServiceTest.java");

        int exitCode = runAppReturningExitCode(
                new String[] { "-manual-prepare", workDir.toString(), sourceDir.toString() });

        assertEquals(0, exitCode);
    }

    // -------------------------------------------------------------------------
    // Consume phase
    // -------------------------------------------------------------------------

    @Test
    void manualConsume_emitsEnrichedCsvForClassesWithResponseFiles(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path workDir = tempDir.resolve("work");       // unused at runtime but required by API
        Path responseDir = tempDir.resolve("responses");
        Files.createDirectories(sourceDir);
        Files.createDirectories(workDir);
        Files.createDirectories(responseDir);

        copyFixture(sourceDir, "AccessControlServiceTest.java");

        // Provide an AI response for AccessControlServiceTest
        String responseJson = """
                {
                  "className": "%s",
                  "classSecurityRelevant": true,
                  "classTags": ["security"],
                  "classReason": "Access control class.",
                  "methods": [
                    {
                      "methodName": "shouldAllowOwnerToReadOwnStatement",
                      "securityRelevant": true,
                      "displayName": "SECURITY: access-control - owner reads own data",
                      "tags": ["security", "access-control"],
                      "reason": "Validates owner-level read access."
                    }
                  ]
                }
                """.formatted(FQCN_ACCESS_CONTROL);
        Files.writeString(responseDir.resolve(FQCN_ACCESS_CONTROL + ".response.txt"), responseJson,
                StandardCharsets.UTF_8);

        String output = runAppCapturingStdout(new String[] {
                "-manual-consume", workDir.toString(), responseDir.toString(),
                sourceDir.toString()
        });

        List<String> lines = nonEmptyLines(output);

        // CSV header includes AI columns
        assertEquals("fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason",
                lines.get(0), "Should emit full AI-enriched CSV header");

        // Find the enriched row
        String enrichedRow = lines.stream()
                .filter(l -> l.contains("shouldAllowOwnerToReadOwnStatement"))
                .findFirst()
                .orElse(null);
        assertNotNull(enrichedRow, "Should find row for shouldAllowOwnerToReadOwnStatement");
        assertTrue(enrichedRow.contains("true"), "Row should contain securityRelevant=true");
        assertTrue(enrichedRow.contains("SECURITY: access-control - owner reads own data"),
                "Row should contain AI display name");
        assertTrue(enrichedRow.contains("security"), "Row should contain AI tags");
    }

    @Test
    void manualConsume_emitsEmptyAiColumnsForClassesWithoutResponseFile(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path workDir = tempDir.resolve("work");
        Path responseDir = tempDir.resolve("responses");
        Files.createDirectories(sourceDir);
        Files.createDirectories(workDir);
        Files.createDirectories(responseDir);  // empty — no response files

        copyFixture(sourceDir, "AccessControlServiceTest.java");

        String output = runAppCapturingStdout(new String[] {
                "-manual-consume", workDir.toString(), responseDir.toString(),
                sourceDir.toString()
        });

        List<String> lines = nonEmptyLines(output);

        // Header still present with AI columns
        assertEquals("fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason",
                lines.get(0));

        // All data rows should have empty AI columns (four trailing empty fields → four commas at end)
        for (int i = 1; i < lines.size(); i++) {
            String row = lines.get(i);
            assertTrue(row.startsWith(FQCN_ACCESS_CONTROL), "Row should belong to the expected class");
            assertTrue(row.endsWith(",,,,"), "AI columns should be empty for: " + row);
        }
    }

    @Test
    void manualConsume_mixesEnrichedAndEmptyRowsForPartialResponses(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path workDir = tempDir.resolve("work");
        Path responseDir = tempDir.resolve("responses");
        Files.createDirectories(sourceDir);
        Files.createDirectories(workDir);
        Files.createDirectories(responseDir);

        // Two fixture files: one gets a response, one does not
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        copyFixture(sourceDir, "AuditLoggingTest.java");

        // Only provide response for AccessControlServiceTest
        String responseJson = """
                {
                  "className": "%s",
                  "classSecurityRelevant": true,
                  "classTags": ["security"],
                  "classReason": "Access control.",
                  "methods": [
                    {
                      "methodName": "shouldAllowOwnerToReadOwnStatement",
                      "securityRelevant": true,
                      "displayName": "SECURITY: access-control - owner",
                      "tags": ["security"],
                      "reason": "owner access"
                    }
                  ]
                }
                """.formatted(FQCN_ACCESS_CONTROL);
        Files.writeString(responseDir.resolve(FQCN_ACCESS_CONTROL + ".response.txt"), responseJson,
                StandardCharsets.UTF_8);

        String output = runAppCapturingStdout(new String[] {
                "-manual-consume", workDir.toString(), responseDir.toString(),
                sourceDir.toString()
        });

        List<String> lines = nonEmptyLines(output);
        assertTrue(lines.size() > 1, "Should have at least one data row");

        // Row for the enriched method should contain AI data
        String enrichedRow = lines.stream()
                .filter(l -> l.contains("shouldAllowOwnerToReadOwnStatement"))
                .findFirst()
                .orElse(null);
        assertNotNull(enrichedRow);
        assertTrue(enrichedRow.contains("true"));
        assertTrue(enrichedRow.contains("SECURITY: access-control - owner"));

        // Rows for AuditLoggingTest should have empty AI columns
        long emptyAiRows = lines.stream()
                .filter(l -> l.startsWith(FQCN_AUDIT))
                .filter(l -> l.endsWith(",,,,"))
                .count();
        assertTrue(emptyAiRows > 0, "AuditLoggingTest rows should have empty AI columns");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void copyFixture(Path destDir, String fixtureFileName) throws IOException {
        String resourcePath = "/fixtures/" + fixtureFileName + ".txt";
        try (InputStream in = MethodAtlasAppManualTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing test resource: " + resourcePath);
            Files.copy(in, destDir.resolve(fixtureFileName));
        }
    }

    private static String runAppCapturingStdout(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(args, out);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static int runAppReturningExitCode(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            return MethodAtlasApp.run(args, out);
        }
    }

    private static List<String> nonEmptyLines(String text) {
        String[] parts = text.split("\\R");
        List<String> lines = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }
}
