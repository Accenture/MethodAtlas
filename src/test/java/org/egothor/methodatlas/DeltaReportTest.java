package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.egothor.methodatlas.api.ScanRecord;
import org.egothor.methodatlas.emit.DeltaEmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DeltaReport} and {@link DeltaEmitter}.
 */
@Tag("unit")
@Tag("delta")
class DeltaReportTest {

    // -------------------------------------------------------------------------
    // CSV parser unit tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseCsvLine handles plain unquoted fields")
    @Tag("positive")
    void parseCsvLine_unquotedFields_parsed() {
        List<String> fields = DeltaReport.parseCsvLine("a,b,c");
        assertEquals(List.of("a", "b", "c"), fields);
    }

    @Test
    @DisplayName("parseCsvLine handles quoted field containing a comma")
    @Tag("positive")
    void parseCsvLine_quotedFieldWithComma_parsedCorrectly() {
        List<String> fields = DeltaReport.parseCsvLine("a,\"b,c\",d");
        assertEquals(List.of("a", "b,c", "d"), fields);
    }

    @Test
    @DisplayName("parseCsvLine handles escaped double-quote inside quoted field")
    @Tag("positive")
    void parseCsvLine_escapedDoubleQuote_parsedCorrectly() {
        List<String> fields = DeltaReport.parseCsvLine("\"a\"\"b\",c");
        assertEquals(List.of("a\"b", "c"), fields);
    }

    @Test
    @DisplayName("parseCsvLine handles trailing comma as empty last field")
    @Tag("positive")
    void parseCsvLine_trailingComma_producesEmptyLastField() {
        List<String> fields = DeltaReport.parseCsvLine("a,b,");
        assertEquals(List.of("a", "b", ""), fields);
    }

    @Test
    @DisplayName("parseCsvLine handles consecutive empty fields")
    @Tag("positive")
    void parseCsvLine_consecutiveCommas_producesEmptyFields() {
        List<String> fields = DeltaReport.parseCsvLine("a,,c");
        assertEquals(List.of("a", "", "c"), fields);
    }

    // -------------------------------------------------------------------------
    // DeltaReport.compute — no changes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("identical before and after files produce zero entries and correct unchanged count")
    @Tag("positive")
    void compute_identicalFiles_noEntries(@TempDir Path tmp) throws IOException {
        String csv = """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,
                com.acme.FooTest,test_two,3,fast
                """;
        Path before = write(tmp, "before.csv", csv);
        Path after = write(tmp, "after.csv", csv);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertTrue(result.entries().isEmpty());
        assertEquals(2, result.unchangedCount());
        assertEquals(2, result.totalBefore());
        assertEquals(2, result.totalAfter());
        assertEquals(0, result.addedCount());
        assertEquals(0, result.removedCount());
        assertEquals(0, result.modifiedCount());
    }

    // -------------------------------------------------------------------------
    // ADDED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("method present in after but not before is reported as ADDED")
    @Tag("positive")
    void compute_methodAddedInAfter_detectedAsAdded(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,
                com.acme.FooTest,test_two,3,
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals(1, result.addedCount());
        DeltaEntry entry = result.entries().get(0);
        assertEquals(DeltaEntry.ChangeType.ADDED, entry.changeType());
        assertEquals("com.acme.FooTest", entry.record().fqcn());
        assertEquals("test_two", entry.record().method());
        assertNull(entry.before());
        assertNotNull(entry.after());
    }

    // -------------------------------------------------------------------------
    // REMOVED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("method present in before but absent from after is reported as REMOVED")
    @Tag("positive")
    void compute_methodRemovedInAfter_detectedAsRemoved(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,
                com.acme.FooTest,test_two,3,
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals(1, result.removedCount());
        DeltaEntry entry = result.entries().get(0);
        assertEquals(DeltaEntry.ChangeType.REMOVED, entry.changeType());
        assertEquals("test_two", entry.record().method());
        assertNotNull(entry.before());
        assertNull(entry.after());
    }

    // -------------------------------------------------------------------------
    // MODIFIED — source change via content_hash
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("differing content_hash with both columns present is reported as MODIFIED with 'source'")
    @Tag("positive")
    void compute_contentHashDiffers_reportedAsSourceModified(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags,display_name,content_hash
                com.acme.FooTest,test_one,5,,,aaa
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name,content_hash
                com.acme.FooTest,test_one,5,,,bbb
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals(1, result.modifiedCount());
        DeltaEntry entry = result.entries().get(0);
        assertTrue(entry.changedFields().contains("source"));
    }

    @Test
    @DisplayName("content_hash present only in one file is not reported as MODIFIED")
    @Tag("positive")
    void compute_contentHashOnlyInOne_notReportedAsModified(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name,content_hash
                com.acme.FooTest,test_one,5,,,abc123
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertTrue(result.entries().isEmpty(), "no change should be reported when content_hash is absent from one file");
    }

    // -------------------------------------------------------------------------
    // MODIFIED — AI classification change
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ai_security_relevant flip is reported as MODIFIED")
    @Tag("positive")
    void compute_aiSecurityRelevantFlips_reportedAsModified(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason
                com.acme.CryptoTest,test_encrypt,8,,,false,,,
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason
                com.acme.CryptoTest,test_encrypt,8,,,true,,security;crypto,verifies AES-GCM
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals(1, result.modifiedCount());
        DeltaEntry entry = result.entries().get(0);
        assertTrue(entry.changedFields().contains("ai_security_relevant"));
        assertTrue(entry.changedFields().contains("ai_tags"));
        assertFalse(entry.before().aiSecurityRelevant());
        assertTrue(entry.after().aiSecurityRelevant());
    }

    // -------------------------------------------------------------------------
    // MODIFIED — LOC change
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("changed LOC is reported in changedFields")
    @Tag("positive")
    void compute_locChanged_reportedAsModified(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,9,
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals(1, result.modifiedCount());
        assertTrue(result.entries().get(0).changedFields().contains("loc"));
    }

    // -------------------------------------------------------------------------
    // MODIFIED — display_name change
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("changed display_name is reported in changedFields when column present in both files")
    @Tag("positive")
    void compute_displayNameChanged_reportedAsModified(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,,old display
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,,new display
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals(1, result.modifiedCount());
        assertTrue(result.entries().get(0).changedFields().contains("display_name"));
    }

    @Test
    @DisplayName("display_name added (empty → value) is reported as MODIFIED")
    @Tag("positive")
    void compute_displayNameAdded_reportedAsModified(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,,
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,,SECURITY: auth - checks login
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals(1, result.modifiedCount());
        assertTrue(result.entries().get(0).changedFields().contains("display_name"));
    }

    @Test
    @DisplayName("display_name column absent in before vs non-empty value in after is reported as MODIFIED")
    @Tag("positive")
    void compute_displayNameAbsentInBeforeValueInAfter_reportedAsModified(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags
                com.acme.FooTest,test_one,5,
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,,SECURITY: auth - checks login
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals(1, result.modifiedCount());
        assertTrue(result.entries().get(0).changedFields().contains("display_name"));
    }

    @Test
    @DisplayName("display_name column absent in before vs empty in after is not reported as MODIFIED")
    @Tag("positive")
    void compute_displayNameAbsentInBeforeEmptyInAfter_notReportedAsModified(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags
                com.acme.FooTest,test_one,5,
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,,
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertTrue(result.entries().isEmpty(),
                "null (column absent) and empty string (no annotation) are both 'no annotation'");
    }

    @Test
    @DisplayName("emitted report contains 'display_name' notation for changed display_name")
    @Tag("positive")
    void emitter_displayNameChanged_appearsInOutput(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv",
                "fqcn,method,loc,tags,display_name\ncom.acme.FooTest,test_one,5,,old\n");
        Path after = write(tmp, "after.csv",
                "fqcn,method,loc,tags,display_name\ncom.acme.FooTest,test_one,5,,new\n");

        String output = runDiff(before, after);

        assertTrue(output.contains("display_name"), output);
        assertTrue(output.contains("~ com.acme.FooTest  test_one"), output);
    }

    // -------------------------------------------------------------------------
    // Metadata comment lines
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("scan_timestamp from metadata comment is forwarded to DeltaResult")
    @Tag("positive")
    void compute_metadataTimestamp_extractedCorrectly(@TempDir Path tmp) throws IOException {
        String csv = """
                # tool_version: 1.0.0
                # scan_timestamp: 2026-04-10T09:00:00Z
                # taxonomy: built-in/default
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,
                """;
        Path before = write(tmp, "before.csv", csv);
        Path after = write(tmp, "after.csv", csv);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals("2026-04-10T09:00:00Z", result.beforeTimestamp());
        assertEquals("2026-04-10T09:00:00Z", result.afterTimestamp());
    }

    // -------------------------------------------------------------------------
    // Security-relevant counts
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("security-relevant counts are tracked correctly across both files")
    @Tag("positive")
    void compute_securityRelevantCounts_correct(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason
                com.acme.AuthTest,test_a,5,,,true,,,
                com.acme.AuthTest,test_b,3,,,false,,,
                """);
        Path after = write(tmp, "after.csv", """
                fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason
                com.acme.AuthTest,test_a,5,,,true,,,
                com.acme.AuthTest,test_b,3,,,true,,,
                com.acme.AuthTest,test_c,4,,,true,,,
                """);

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals(1, result.securityRelevantBefore());
        assertEquals(3, result.securityRelevantAfter());
    }

    // -------------------------------------------------------------------------
    // Empty files
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("empty after file reports all before methods as REMOVED")
    @Tag("positive")
    void compute_emptyAfterFile_allRemoved(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", """
                fqcn,method,loc,tags,display_name
                com.acme.FooTest,test_one,5,
                com.acme.FooTest,test_two,3,
                """);
        Path after = write(tmp, "after.csv", "fqcn,method,loc,tags,display_name\n");

        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);

        assertEquals(2, result.removedCount());
        assertEquals(0, result.addedCount());
    }

    // -------------------------------------------------------------------------
    // Required column missing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CSV missing required 'fqcn' column throws IllegalArgumentException")
    @Tag("negative")
    void compute_missingFqcnColumn_throwsIllegalArgumentException(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", "method,loc\ntest_one,5\n");
        Path after = write(tmp, "after.csv", "method,loc\ntest_one,5\n");

        assertThrows(IllegalArgumentException.class, () -> DeltaReport.compute(before, after));
    }

    // -------------------------------------------------------------------------
    // Emitter output
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emitted report contains ADDED line for new method")
    @Tag("positive")
    void emitter_addedMethod_appearsInOutput(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv", "fqcn,method,loc,tags,display_name\ncom.acme.FooTest,test_one,5,\n");
        Path after = write(tmp, "after.csv",
                "fqcn,method,loc,tags,display_name\ncom.acme.FooTest,test_one,5,\ncom.acme.FooTest,test_two,3,\n");

        String output = runDiff(before, after);

        assertTrue(output.contains("+ com.acme.FooTest  test_two"), output);
    }

    @Test
    @DisplayName("emitted report contains 'No changes detected' for identical files")
    @Tag("positive")
    void emitter_identicalFiles_noChangesMessage(@TempDir Path tmp) throws IOException {
        String csv = "fqcn,method,loc,tags,display_name\ncom.acme.FooTest,test_one,5,\n";
        Path before = write(tmp, "before.csv", csv);
        Path after = write(tmp, "after.csv", csv);

        String output = runDiff(before, after);

        assertTrue(output.contains("No changes detected"), output);
    }

    @Test
    @DisplayName("emitted report summary line contains correct counts")
    @Tag("positive")
    void emitter_summaryLineContainsCounts(@TempDir Path tmp) throws IOException {
        Path before = write(tmp, "before.csv",
                "fqcn,method,loc,tags,display_name\ncom.acme.FooTest,test_one,5,\ncom.acme.FooTest,test_two,3,\n");
        Path after = write(tmp, "after.csv",
                "fqcn,method,loc,tags,display_name\ncom.acme.FooTest,test_one,5,\ncom.acme.FooTest,test_three,4,\n");

        String output = runDiff(before, after);

        assertTrue(output.contains("1 added"), output);
        assertTrue(output.contains("1 removed"), output);
        assertTrue(output.contains("1 unchanged"), output);
    }

    // -------------------------------------------------------------------------
    // MethodAtlasApp integration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-diff flag routes to delta report and exits 0")
    @Tag("positive")
    void app_diffFlag_producesReport(@TempDir Path tmp) throws IOException {
        String csv = "fqcn,method,loc,tags,display_name\ncom.acme.FooTest,test_one,5,\n";
        Path before = write(tmp, "before.csv", csv);
        Path after = write(tmp, "after.csv", csv);

        StringWriter sw = new StringWriter();
        int exitCode = MethodAtlasApp.run(
                new String[]{"-diff", before.toString(), after.toString()},
                new PrintWriter(sw));

        assertEquals(0, exitCode);
        assertTrue(sw.toString().contains("MethodAtlas delta report"), sw.toString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String runDiff(Path before, Path after) throws IOException {
        DeltaReport.DeltaResult result = DeltaReport.compute(before, after);
        StringWriter sw = new StringWriter();
        DeltaEmitter.emit(result, new PrintWriter(sw));
        return sw.toString();
    }
}
