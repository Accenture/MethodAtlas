package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.egothor.methodatlas.api.ScanRecord;
import org.egothor.methodatlas.emit.DeltaEmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeltaEmitter}.
 *
 * <p>
 * Tests cover all formatting paths: header (with and without timestamps,
 * singular/plural method count), entry change-type indicators (+/-/~),
 * per-field change detail notation, and summary counters including
 * positive/negative/zero security-count delta.
 * </p>
 */
@Tag("unit")
@Tag("delta")
class DeltaEmitterTest {

    // -------------------------------------------------------------------------
    // Header
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emit includes both before and after filenames in header")
    @Tag("positive")
    void emit_header_containsBeforeAndAfterFilenames() {
        String output = emit(result(
                "before.csv", "after.csv", null, null, 10, 12, 2, 3, List.of(), 10));

        assertTrue(output.contains("before.csv"), "header should mention before filename");
        assertTrue(output.contains("after.csv"), "header should mention after filename");
    }

    @Test
    @DisplayName("emit header contains 'MethodAtlas delta report' title")
    @Tag("positive")
    void emit_header_containsTitle() {
        String output = emit(result(
                "a.csv", "b.csv", null, null, 5, 5, 1, 1, List.of(), 5));

        assertTrue(output.contains("MethodAtlas delta report"), "header title should be present");
    }

    @Test
    @DisplayName("emit header includes scanned timestamp when provided")
    @Tag("positive")
    void emit_header_withTimestamps_showsScanTimestamps() {
        String output = emit(result(
                "a.csv", "b.csv",
                "2026-04-10T09:00:00Z", "2026-04-24T14:30:00Z",
                10, 12, 2, 3, List.of(), 10));

        assertTrue(output.contains("2026-04-10T09:00:00Z"), "before timestamp should appear in header");
        assertTrue(output.contains("2026-04-24T14:30:00Z"), "after timestamp should appear in header");
        assertTrue(output.contains("scanned:"), "scanned: label should appear when timestamp is provided");
    }

    @Test
    @DisplayName("emit header omits 'scanned:' prefix when timestamp is null")
    @Tag("edge-case")
    void emit_header_withNullTimestamp_omitsScannedPrefix() {
        String output = emit(result(
                "a.csv", "b.csv", null, null, 10, 10, 2, 2, List.of(), 10));

        assertFalse(output.contains("scanned:"), "scanned: label should be absent when timestamp is null");
    }

    @Test
    @DisplayName("emit header uses singular 'method' when total is 1")
    @Tag("edge-case")
    void emit_header_singularCount_showsMethod() {
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 0, List.of(), 1));

        assertTrue(output.contains("1 method "), "header should use singular 'method' for count 1");
        assertFalse(output.contains("1 methods"), "header must not use plural for count 1");
    }

    @Test
    @DisplayName("emit header uses plural 'methods' when total is not 1")
    @Tag("positive")
    void emit_header_pluralCount_showsMethods() {
        String output = emit(result(
                "a.csv", "b.csv", null, null, 5, 5, 0, 0, List.of(), 5));

        assertTrue(output.contains("5 methods"), "header should use plural 'methods' for count != 1");
    }

    @Test
    @DisplayName("emit header shows zero security-relevant count correctly")
    @Tag("edge-case")
    void emit_header_zeroSecurityRelevant_showsZero() {
        String output = emit(result(
                "a.csv", "b.csv", null, null, 5, 5, 0, 0, List.of(), 5));

        assertTrue(output.contains("0 security-relevant"), "header should show zero security-relevant count");
    }

    // -------------------------------------------------------------------------
    // Entries — no-changes path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emit outputs 'No changes detected.' when entries list is empty")
    @Tag("edge-case")
    void emit_noEntries_showsNoChangesDetected() {
        String output = emit(result(
                "a.csv", "b.csv", null, null, 5, 5, 1, 1, List.of(), 5));

        assertTrue(output.contains("No changes detected."), "no-change message should appear when entries list is empty");
    }

    // -------------------------------------------------------------------------
    // Entries — change-type indicators
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ADDED entry is prefixed with '+'")
    @Tag("positive")
    void emit_addedEntry_showsPlusSymbol() {
        ScanRecord rec = scanRecord("com.acme.AuthTest", "testLogin", 8, null);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 0, 1, 0, 0,
                List.of(DeltaEntry.added(rec)), 0));

        assertTrue(output.contains("+ com.acme.AuthTest  testLogin"),
                "ADDED entry should be prefixed with '+': " + output);
    }

    @Test
    @DisplayName("REMOVED entry is prefixed with '-'")
    @Tag("positive")
    void emit_removedEntry_showsMinusSymbol() {
        ScanRecord rec = scanRecord("com.acme.LegacyTest", "testOld", 5, null);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 0, 0, 0,
                List.of(DeltaEntry.removed(rec)), 0));

        assertTrue(output.contains("- com.acme.LegacyTest  testOld"),
                "REMOVED entry should be prefixed with '-': " + output);
    }

    @Test
    @DisplayName("MODIFIED entry is prefixed with '~'")
    @Tag("positive")
    void emit_modifiedEntry_showsTilde() {
        ScanRecord before = scanRecord("com.acme.CryptoTest", "testRoundTrip", 5, false);
        ScanRecord after  = scanRecord("com.acme.CryptoTest", "testRoundTrip", 5, true);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 1,
                List.of(DeltaEntry.modified(before, after, Set.of("ai_security_relevant"))), 0));

        assertTrue(output.contains("~ com.acme.CryptoTest  testRoundTrip"),
                "MODIFIED entry should be prefixed with '~': " + output);
    }

    @Test
    @DisplayName("MODIFIED entry with empty changedFields set produces no bracket detail")
    @Tag("edge-case")
    void emit_modifiedEntry_emptyChangedFields_noBrackets() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 0,
                List.of(DeltaEntry.modified(before, after, Set.of())), 0));

        assertFalse(output.contains("["), "no bracket detail should appear when changedFields is empty");
    }

    @Test
    @DisplayName("MODIFIED entry with changedFields produces bracketed change detail")
    @Tag("positive")
    void emit_modifiedEntry_withChangedFields_showsBrackets() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 10, null);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 0,
                List.of(DeltaEntry.modified(before, after, Set.of("loc"))), 0));

        assertTrue(output.contains("["), "bracket detail should appear when changedFields is non-empty: " + output);
        assertTrue(output.contains("]"), "closing bracket should be present");
    }

    // -------------------------------------------------------------------------
    // formatChangedFields — individual field names
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("'source' changed field is rendered as 'source' in bracket detail")
    @Tag("positive")
    void emit_modifiedEntry_sourceChanged_showsSource() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 0,
                List.of(DeltaEntry.modified(before, after, Set.of("source"))), 0));

        assertTrue(output.contains("[source]"), "source field change should appear in brackets: " + output);
    }

    @Test
    @DisplayName("'loc' changed field shows N → M arrow notation")
    @Tag("positive")
    void emit_modifiedEntry_locChanged_showsLocArrow() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 12, null);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 0,
                List.of(DeltaEntry.modified(before, after, Set.of("loc"))), 0));

        assertTrue(output.contains("loc: 5"), "loc change should show before value: " + output);
        assertTrue(output.contains("12"), "loc change should show after value: " + output);
    }

    @Test
    @DisplayName("'tags' changed field is rendered as 'tags' in bracket detail")
    @Tag("positive")
    void emit_modifiedEntry_tagsChanged_showsTags() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 0,
                List.of(DeltaEntry.modified(before, after, Set.of("tags"))), 0));

        assertTrue(output.contains("[tags]"), "tags change should appear in brackets: " + output);
    }

    @Test
    @DisplayName("'display_name' changed field is rendered as 'display_name' in bracket detail")
    @Tag("positive")
    void emit_modifiedEntry_displayNameChanged_showsDisplayName() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 0,
                List.of(DeltaEntry.modified(before, after, Set.of("display_name"))), 0));

        assertTrue(output.contains("display_name"), "display_name change should appear in detail: " + output);
    }

    @Test
    @DisplayName("'ai_security_relevant' changed false→true is rendered with arrow notation")
    @Tag("positive")
    void emit_modifiedEntry_aiSecurityChangedFalseToTrue_showsArrow() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, false);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 5, true);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 1,
                List.of(DeltaEntry.modified(before, after, Set.of("ai_security_relevant"))), 0));

        assertTrue(output.contains("security: false"), "before-value should appear: " + output);
        assertTrue(output.contains("true"), "after-value should appear: " + output);
    }

    @Test
    @DisplayName("'ai_security_relevant' changed true→false is rendered with arrow notation")
    @Tag("positive")
    void emit_modifiedEntry_aiSecurityChangedTrueToFalse_showsArrow() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, true);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 5, false);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 1, 0,
                List.of(DeltaEntry.modified(before, after, Set.of("ai_security_relevant"))), 0));

        assertTrue(output.contains("security: true"), "before-value should appear: " + output);
        assertTrue(output.contains("false"), "after-value should appear: " + output);
    }

    @Test
    @DisplayName("'ai_tags' changed field is rendered as 'ai_tags' in bracket detail")
    @Tag("positive")
    void emit_modifiedEntry_aiTagsChanged_showsAiTags() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 0,
                List.of(DeltaEntry.modified(before, after, Set.of("ai_tags"))), 0));

        assertTrue(output.contains("ai_tags"), "ai_tags change should appear in detail: " + output);
    }

    @Test
    @DisplayName("unknown changed field is appended as-is to bracket detail")
    @Tag("edge-case")
    void emit_modifiedEntry_unknownField_appendedAsIs() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 0,
                List.of(DeltaEntry.modified(before, after, Set.of("custom_field"))), 0));

        assertTrue(output.contains("custom_field"), "unknown field name should appear verbatim: " + output);
    }

    @Test
    @DisplayName("multiple changed fields are joined with '; ' in bracket detail")
    @Tag("positive")
    void emit_modifiedEntry_multipleChangedFields_joinedBySemicolon() {
        ScanRecord before = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        ScanRecord after  = scanRecord("com.acme.FooTest", "testFoo", 10, null);
        // Use fields that produce deterministic single-word output: source and tags
        String output = emit(result(
                "a.csv", "b.csv", null, null, 1, 1, 0, 0,
                List.of(DeltaEntry.modified(before, after, Set.of("source", "tags"))), 0));

        // Both "source" and "tags" should be in the bracketed detail joined by "; "
        String bracketContent = output.substring(output.indexOf('[') + 1, output.indexOf(']'));
        assertTrue(bracketContent.contains("source"), "source should be in detail: " + bracketContent);
        assertTrue(bracketContent.contains("tags"), "tags should be in detail: " + bracketContent);
        assertTrue(bracketContent.contains("; "), "fields should be joined with '; ': " + bracketContent);
    }

    // -------------------------------------------------------------------------
    // Summary counters
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("emit summary shows added/removed/modified/unchanged counts")
    @Tag("positive")
    void emit_summary_showsAllCounts() {
        ScanRecord rec = scanRecord("com.acme.FooTest", "testFoo", 5, null);
        ScanRecord rec2 = scanRecord("com.acme.BarTest", "testBar", 3, null);
        ScanRecord rec3before = scanRecord("com.acme.BazTest", "testBaz", 4, null);
        ScanRecord rec3after  = scanRecord("com.acme.BazTest", "testBaz", 8, null);
        List<DeltaEntry> entries = List.of(
                DeltaEntry.added(rec),
                DeltaEntry.removed(rec2),
                DeltaEntry.modified(rec3before, rec3after, Set.of("loc")));
        String output = emit(result("a.csv", "b.csv", null, null, 3, 3, 0, 0, entries, 7));

        assertTrue(output.contains("1 added"), "summary should show 1 added: " + output);
        assertTrue(output.contains("1 removed"), "summary should show 1 removed: " + output);
        assertTrue(output.contains("1 modified"), "summary should show 1 modified: " + output);
        assertTrue(output.contains("7 unchanged"), "summary should show 7 unchanged: " + output);
    }

    @Test
    @DisplayName("emit summary shows positive security delta with '+' prefix")
    @Tag("positive")
    void emit_summary_deltaPositive_showsPlusPrefix() {
        String output = emit(result(
                "a.csv", "b.csv", null, null, 10, 12, 5, 8, List.of(), 10));

        assertTrue(output.contains("(+3)"), "positive delta should show (+3): " + output);
    }

    @Test
    @DisplayName("emit summary shows negative security delta without '+' prefix")
    @Tag("positive")
    void emit_summary_deltaNegative_showsNegativeValue() {
        String output = emit(result(
                "a.csv", "b.csv", null, null, 10, 8, 8, 5, List.of(), 8));

        assertTrue(output.contains("(-3)"), "negative delta should show (-3): " + output);
    }

    @Test
    @DisplayName("emit summary shows '(no change)' when security count is unchanged")
    @Tag("edge-case")
    void emit_summary_deltaZero_showsNoChange() {
        String output = emit(result(
                "a.csv", "b.csv", null, null, 10, 10, 5, 5, List.of(), 10));

        assertTrue(output.contains("(no change)"), "zero delta should show '(no change)': " + output);
    }

    @Test
    @DisplayName("emit summary shows correct before→after arrow for security-relevant counts")
    @Tag("positive")
    void emit_summary_showsSecurityCountsWithArrow() {
        String output = emit(result(
                "a.csv", "b.csv", null, null, 10, 12, 3, 7, List.of(), 10));

        assertTrue(output.contains("security-relevant: 3"), "before security count should appear: " + output);
        assertTrue(output.contains("7"), "after security count should appear: " + output);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ScanRecord scanRecord(String fqcn, String method, int loc, Boolean aiSecurityRelevant) {
        return new ScanRecord(fqcn, method, loc, List.of(), null, null,
                aiSecurityRelevant, null, null, null, null, null, null);
    }

    private static DeltaReport.DeltaResult result(
            String beforePath, String afterPath,
            String beforeTimestamp, String afterTimestamp,
            int totalBefore, int totalAfter,
            int secBefore, int secAfter,
            List<DeltaEntry> entries,
            int unchanged) {
        return new DeltaReport.DeltaResult(
                Path.of(beforePath), Path.of(afterPath),
                beforeTimestamp, afterTimestamp,
                totalBefore, totalAfter,
                secBefore, secAfter,
                entries, unchanged);
    }

    private static String emit(DeltaReport.DeltaResult result) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            DeltaEmitter.emit(result, pw);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
