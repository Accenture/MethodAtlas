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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Baseline end-to-end regression tests for {@link MethodAtlasApp} output
 * formats with AI enrichment disabled.
 *
 * <p>
 * These tests copy predefined Java fixture files from
 * {@code src/test/resources/fixtures} into a temporary directory and execute
 * {@link MethodAtlasApp#main(String[])} against that directory. The assertions
 * verify the stable non-AI contract of the application:
 * </p>
 * <ul>
 * <li>detected test methods</li>
 * <li>inclusive method LOC values</li>
 * <li>extracted JUnit {@code @Tag} values, including nested {@code @Tags}</li>
 * <li>CSV and plain-text rendering behavior</li>
 * </ul>
 *
 * <p>
 * AI-specific behavior should be tested separately once a dedicated injection
 * seam exists for supplying a mocked
 * {@code org.egothor.methodatlas.ai.AiSuggestionEngine}.
 * </p>
 */
public class MethodAtlasAppTest {

    @Test
    public void csvMode_detectsMethodsLocAndTags(@TempDir Path tempDir) throws Exception {
        copyStandardFixtures(tempDir);

        String output = runAppCapturingStdout(new String[] { tempDir.toString() });

        List<String> lines = nonEmptyLines(output);
        assertEquals(18, lines.size(), "Expected header + 17 records");

        assertEquals("fqcn,method,loc,tags", lines.get(0));

        Map<String, CsvRow> rows = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            CsvRow row = parseCsvRow(lines.get(i));
            rows.put(row.fqcn + "#" + row.method, row);
        }

        assertCsvRow(rows, "com.acme.tests.SampleOneTest", "alpha", 8, List.of("fast", "crypto"));
        assertCsvRow(rows, "com.acme.tests.SampleOneTest", "beta", 6, List.of("param"));
        assertCsvRow(rows, "com.acme.tests.SampleOneTest", "gamma", 4, List.of("nested1", "nested2"));
        assertCsvRow(rows, "com.acme.other.AnotherTest", "delta", 3, List.of());
    }

    @Test
    public void plainMode_detectsMethodsLocAndTags(@TempDir Path tempDir) throws Exception {
        copyStandardFixtures(tempDir);

        String output = runAppCapturingStdout(new String[] { "-plain", tempDir.toString() });

        List<String> lines = nonEmptyLines(output);
        assertEquals(17, lines.size(), "Expected 17 method lines");

        Map<String, PlainRow> rows = new HashMap<>();
        for (String line : lines) {
            PlainRow row = parsePlainRow(line);
            rows.put(row.fqcn + "#" + row.method, row);
        }

        assertPlainRow(rows, "com.acme.tests.SampleOneTest", "alpha", 8, "fast;crypto");
        assertPlainRow(rows, "com.acme.tests.SampleOneTest", "beta", 6, "param");
        assertPlainRow(rows, "com.acme.tests.SampleOneTest", "gamma", 4, "nested1;nested2");
        assertPlainRow(rows, "com.acme.other.AnotherTest", "delta", 3, "-");
    }

    @Test
    public void testAnnotation_customAnnotationIsRecognised(@TempDir Path tempDir) throws Exception {
        // Fixture with one standard @Test and one custom @MyTest annotation
        String javaSource = """
                package com.acme.tests;
                public class CustomAnnotationTest {
                    @MyTest
                    public void shouldMatchCustomAnnotation() {}
                    @Test
                    public void shouldMatchDefaultAnnotation() {}
                }
                """;
        Files.writeString(tempDir.resolve("CustomAnnotationTest.java"), javaSource, StandardCharsets.UTF_8);

        // Default behaviour: only @Test is recognised
        String defaultOutput = runAppCapturingStdout(new String[] { tempDir.toString() });
        List<String> defaultLines = nonEmptyLines(defaultOutput);
        // header + 1 row (shouldMatchDefaultAnnotation only)
        assertEquals(2, defaultLines.size());
        assertTrue(defaultLines.stream().anyMatch(l -> l.contains("shouldMatchDefaultAnnotation")));
        assertFalse(defaultLines.stream().anyMatch(l -> l.contains("shouldMatchCustomAnnotation")));

        // With -test-annotation replacing the default: only @MyTest is recognised
        String customOutput = runAppCapturingStdout(new String[] {
                "-test-annotation", "MyTest", tempDir.toString()
        });
        List<String> customLines = nonEmptyLines(customOutput);
        assertEquals(2, customLines.size());
        assertTrue(customLines.stream().anyMatch(l -> l.contains("shouldMatchCustomAnnotation")));
        assertFalse(customLines.stream().anyMatch(l -> l.contains("shouldMatchDefaultAnnotation")));

        // With -test-annotation adding to the default: both are recognised
        String bothOutput = runAppCapturingStdout(new String[] {
                "-test-annotation", "Test", "-test-annotation", "MyTest", tempDir.toString()
        });
        List<String> bothLines = nonEmptyLines(bothOutput);
        assertEquals(3, bothLines.size()); // header + 2 rows
        assertTrue(bothLines.stream().anyMatch(l -> l.contains("shouldMatchCustomAnnotation")));
        assertTrue(bothLines.stream().anyMatch(l -> l.contains("shouldMatchDefaultAnnotation")));
    }

    @Test
    public void emitMetadata_prependsCommentLinesBeforeHeader(@TempDir Path tempDir) throws Exception {
        copyStandardFixtures(tempDir);

        String output = runAppCapturingStdout(new String[] { "-emit-metadata", tempDir.toString() });
        List<String> lines = nonEmptyLines(output);

        // Metadata lines are present
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("# tool_version:")),
                "Should contain tool_version metadata");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("# scan_timestamp:")),
                "Should contain scan_timestamp metadata");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("# taxonomy:")),
                "Should contain taxonomy metadata");

        // Metadata precedes the CSV header
        int toolVersionIdx = -1;
        int headerIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("# tool_version:")) toolVersionIdx = i;
            if ("fqcn,method,loc,tags".equals(lines.get(i))) headerIdx = i;
        }
        assertTrue(toolVersionIdx >= 0 && headerIdx >= 0 && toolVersionIdx < headerIdx,
                "Metadata must appear before the CSV header");

        // taxonomy value indicates AI is not enabled
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("# taxonomy:") && l.contains("n/a")),
                "Taxonomy metadata should indicate AI is disabled");
    }

    private static void copyStandardFixtures(Path tempDir) throws IOException {
        copyFixture(tempDir, "SampleOneTest.java");
        copyFixture(tempDir, "AnotherTest.java");
        copyFixture(tempDir, "AccessControlServiceTest.java");
        copyFixture(tempDir, "PathTraversalValidationTest.java");
        copyFixture(tempDir, "AuditLoggingTest.java");
    }

    private static void assertCsvRow(Map<String, CsvRow> rows, String fqcn, String method, int expectedLoc,
            List<String> expectedTags) {

        CsvRow row = rows.get(fqcn + "#" + method);
        assertNotNull(row, "Missing row for " + fqcn + "#" + method);

        assertEquals(expectedLoc, row.loc, "LOC mismatch for " + fqcn + "#" + method);
        assertEquals(expectedTags, row.tags, "Tags mismatch for " + fqcn + "#" + method);
    }

    private static void assertPlainRow(Map<String, PlainRow> rows, String fqcn, String method, int expectedLoc,
            String expectedTagsText) {

        PlainRow row = rows.get(fqcn + "#" + method);
        assertNotNull(row, "Missing row for " + fqcn + "#" + method);

        assertEquals(expectedLoc, row.loc, "LOC mismatch for " + fqcn + "#" + method);
        assertEquals(expectedTagsText, row.tagsText, "Tags mismatch for " + fqcn + "#" + method);
    }

    private static void copyFixture(Path destDir, String fixtureFileName) throws IOException {
        String resourcePath = "/fixtures/" + fixtureFileName + ".txt";
        try (InputStream in = MethodAtlasAppTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing test resource: " + resourcePath);

            Path out = destDir.resolve(fixtureFileName);
            Files.copy(in, out);
        }
    }

    private static String runAppCapturingStdout(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(args, out);
        }
        return baos.toString(StandardCharsets.UTF_8);
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

    private static CsvRow parseCsvRow(String line) {
        List<String> fields = parseCsvFields(line);
        assertEquals(4, fields.size(), "Expected 4 CSV fields, got " + fields.size() + " from: " + line);

        CsvRow row = new CsvRow();
        row.fqcn = fields.get(0);
        row.method = fields.get(1);
        row.loc = Integer.parseInt(fields.get(2));

        String tagsText = fields.get(3);
        row.tags = splitTags(tagsText);

        return row;
    }

    private static List<String> splitTags(String tagsText) {
        List<String> tags = new ArrayList<>();
        if (tagsText == null || tagsText.isEmpty()) {
            return tags;
        }
        String[] parts = tagsText.split(";");
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                tags.add(t);
            }
        }
        return tags;
    }

    /**
     * Minimal CSV parser that supports commas and quotes.
     */
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

    private static PlainRow parsePlainRow(String line) {
        Pattern p = Pattern.compile("^(.*),\\s+(.*),\\s+LOC=(\\d+),\\s+TAGS=(.*)$");
        Matcher m = p.matcher(line);
        assertTrue(m.matches(), "Unexpected plain output line: " + line);

        PlainRow row = new PlainRow();
        row.fqcn = m.group(1).trim();
        row.method = m.group(2).trim();
        row.loc = Integer.parseInt(m.group(3));
        row.tagsText = m.group(4).trim();
        return row;
    }

    private static final class CsvRow {
        private String fqcn;
        private String method;
        private int loc;
        private List<String> tags;
    }

    private static final class PlainRow {
        private String fqcn;
        private String method;
        private int loc;
        private String tagsText;
    }
}