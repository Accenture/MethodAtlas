// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.gui.service.AuditWriter.SavedEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link AuditWriter}.
 *
 * <p>
 * This class is the audit-trail integrity surface for the regulated-
 * environment value proposition. The tests below exercise every documented
 * branch of {@code write()}, the CSV column schema, RFC 4180 quoting, drift
 * computation, the override-YAML cumulative-update behaviour, and the
 * operator-identity inclusion in the {@code note} field.
 * </p>
 *
 * @since 1.0.0
 */
class AuditWriterTest {

    private static SavedEntry entry(String fqcn, String method, int loc,
            List<String> appliedTags, String displayName,
            AiMethodSuggestion suggestion) {
        return new SavedEntry(fqcn, method, loc, appliedTags, displayName, suggestion);
    }

    private static AiMethodSuggestion suggestion(boolean securityRelevant,
            List<String> tags, String reason, double confidence) {
        return new AiMethodSuggestion("m", securityRelevant,
                /* displayName */ null,
                tags, reason, confidence, /* interactionScore */ 0.0);
    }

    private static Path findEvidenceCsv(Path auditDir) throws IOException {
        try (var stream = Files.list(auditDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("methodatlas-")
                            && p.getFileName().toString().endsWith(".csv"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    // ── write() happy path ───────────────────────────────────────────────────

    @Test
    void write_createsAuditDirAndBothArtefacts(@TempDir Path tempDir) throws IOException {
        SavedEntry e = entry("com.acme.Foo", "test_one", 12,
                List.of("security"), "SECURITY: foo",
                suggestion(true, List.of("security"), "Reason", 0.9));

        AuditWriter.write(tempDir, List.of(e), "Jane Smith");

        Path auditDir = tempDir.resolve(".methodatlas");
        assertTrue(Files.isDirectory(auditDir),
                ".methodatlas directory must be created");
        assertTrue(Files.exists(auditDir.resolve("overrides.yaml")));
        assertNotNull(findEvidenceCsv(auditDir));
    }

    // ── Evidence CSV ─────────────────────────────────────────────────────────

    @Test
    void evidenceCsv_headerMatchesDeltaReportSchema(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 5, List.of("security"), null,
                suggestion(true, List.of("security"), null, 0.0))), "");

        String csv = Files.readString(findEvidenceCsv(tempDir.resolve(".methodatlas")),
                StandardCharsets.UTF_8);
        String firstLine = csv.lines().findFirst().orElseThrow();
        assertEquals(
                "fqcn,method,loc,tags,display_name,content_hash,"
                        + "ai_security_relevant,ai_display_name,ai_tags,ai_reason,"
                        + "ai_interaction_score,ai_confidence,tag_ai_drift,tags_added,tags_removed",
                firstLine,
                "Header must be the DeltaReport schema plus the tags_added/tags_removed columns");
    }

    @Test
    void evidenceCsv_quotesFieldsContainingComma(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of("a,b"), null,
                suggestion(true, List.of("a,b"), "with, comma", 0.0))), "");

        String csv = Files.readString(findEvidenceCsv(tempDir.resolve(".methodatlas")),
                StandardCharsets.UTF_8);
        // The reason field "with, comma" must be wrapped in double quotes
        assertTrue(csv.contains("\"with, comma\""),
                "Comma-bearing fields must be quoted per RFC 4180");
    }

    @Test
    void evidenceCsv_doublesEmbeddedQuotes(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of(), null,
                suggestion(true, List.of(), "He said \"hi\"", 0.0))), "");

        String csv = Files.readString(findEvidenceCsv(tempDir.resolve(".methodatlas")),
                StandardCharsets.UTF_8);
        // RFC 4180: each " inside a quoted field is doubled to ""
        assertTrue(csv.contains("\"He said \"\"hi\"\"\""),
                "Embedded double quotes must be doubled per RFC 4180");
    }

    @Test
    void evidenceCsv_locZeroEmittedAsBlank(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 0, List.of(), null, null)), "");

        String csv = Files.readString(findEvidenceCsv(tempDir.resolve(".methodatlas")),
                StandardCharsets.UTF_8);
        // The loc column is blank when no LOC info is available (loc == 0)
        String dataLine = csv.lines().skip(1).findFirst().orElseThrow();
        // fields: fqcn(c),method(m),loc(blank),...
        assertTrue(dataLine.startsWith("c,m,,"),
                "Zero LOC must render as a blank column, not '0'");
    }

    @Test
    void evidenceCsv_nullSuggestionEmitsBlankAiColumns(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 3, List.of("security"), null, null)), "");

        String csv = Files.readString(findEvidenceCsv(tempDir.resolve(".methodatlas")),
                StandardCharsets.UTF_8);
        String dataLine = csv.lines().skip(1).findFirst().orElseThrow();
        // When ai is null, ai_security_relevant, ai_confidence, ai_interaction_score must be blank
        // The line splits into many fields; we check none of them stringify to "false" or "0.0"
        // for the AI columns. The simplest invariant: the ai_security_relevant slot is empty.
        String[] fields = dataLine.split(",", -1);
        assertEquals("", fields[6], "ai_security_relevant must be blank when suggestion is null");
    }

    @Test
    void evidenceCsv_filenameTimestampHasYyyyMMdd_HHmmssShape(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of(), null, null)), "");

        Path csv = findEvidenceCsv(tempDir.resolve(".methodatlas"));
        String name = csv.getFileName().toString();
        // methodatlas-YYYYMMDD-HHmmss.csv
        assertTrue(name.matches("methodatlas-\\d{8}-\\d{6}\\.csv"),
                "Filename must follow methodatlas-YYYYMMDD-HHmmss.csv: " + name);
    }

    private static String[] dataFields(Path tempDir) throws IOException {
        String csv = Files.readString(findEvidenceCsv(tempDir.resolve(".methodatlas")),
                StandardCharsets.UTF_8);
        String dataLine = csv.lines().skip(1).findFirst().orElseThrow();
        return dataLine.split(",", -1);
    }

    // Column indices in the evidence CSV (see header schema).
    private static final int COL_TAG_AI_DRIFT = 12;
    private static final int COL_TAGS_ADDED = 13;
    private static final int COL_TAGS_REMOVED = 14;

    @Test
    void evidenceCsv_driftBothAgreeSecurity_isNone(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1,
                List.of("security"), null,
                suggestion(true, List.of("security"), null, 0.0))), "");

        assertEquals("none", dataFields(tempDir)[COL_TAG_AI_DRIFT],
                "Drift is 'none' when the security tag and AI verdict agree");
    }

    @Test
    void evidenceCsv_driftSecurityTagButAiSaysNo_isTagOnly(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1,
                List.of("security"), null,
                suggestion(false, List.of(), null, 0.0))), "");

        assertEquals("tag-only", dataFields(tempDir)[COL_TAG_AI_DRIFT],
                "Drift is 'tag-only' when a security tag is present but AI says not security-relevant");
    }

    @Test
    void evidenceCsv_driftAiSaysSecurityButNoTag_isAiOnly(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1,
                List.of("auth"), null,
                suggestion(true, List.of("auth"), null, 0.0))), "");

        assertEquals("ai-only", dataFields(tempDir)[COL_TAG_AI_DRIFT],
                "Drift is 'ai-only' when AI says security-relevant but no @Tag(\"security\") is applied");
    }

    @Test
    void evidenceCsv_driftBlankWhenNoSuggestion(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of("security"), null, null)), "");

        String[] fields = dataFields(tempDir);
        assertEquals("", fields[COL_TAG_AI_DRIFT], "Drift is blank when there is no AI suggestion");
        assertEquals("", fields[COL_TAGS_ADDED], "tags_added is blank when there is no AI suggestion");
        assertEquals("", fields[COL_TAGS_REMOVED], "tags_removed is blank when there is no AI suggestion");
    }

    @Test
    void evidenceCsv_tagsAddedAndRemovedRecordTheReviewerDelta(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1,
                List.of("security", "perf"), null,
                suggestion(true, List.of("security", "crypto"), null, 0.0))), "");

        String[] fields = dataFields(tempDir);
        assertEquals("perf", fields[COL_TAGS_ADDED],
                "tags_added lists applied tags the AI did not suggest");
        assertEquals("crypto", fields[COL_TAGS_REMOVED],
                "tags_removed lists AI-suggested tags the reviewer did not apply");
        assertEquals("none", fields[COL_TAG_AI_DRIFT],
                "Security classification still agrees, so tag_ai_drift is 'none' despite the tag-set delta");
    }

    // ── Override YAML ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readOverrides(Path yamlFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> root = mapper.readValue(yamlFile.toFile(), Map.class);
        return (List<Map<String, Object>>) root.get("overrides");
    }

    @Test
    void overrideYaml_newFile_containsOneEntryPerSavedRow(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(
                entry("c", "m1", 1, List.of("security"), null,
                        suggestion(true, List.of("security"), null, 0.0)),
                entry("c", "m2", 2, List.of("security", "auth"), null,
                        suggestion(true, List.of("security", "auth"), null, 0.0))), "");

        var overrides = readOverrides(tempDir.resolve(".methodatlas/overrides.yaml"));
        assertEquals(2, overrides.size());
        assertEquals("c", overrides.get(0).get("fqcn"));
        assertEquals("m1", overrides.get(0).get("method"));
        assertEquals("m2", overrides.get(1).get("method"));
    }

    @Test
    void overrideYaml_securityRelevantTrue_whenTagsApplied(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of("security"), null,
                suggestion(false, List.of(), null, 0.0))), "");

        var overrides = readOverrides(tempDir.resolve(".methodatlas/overrides.yaml"));
        assertEquals(true, overrides.get(0).get("securityRelevant"),
                "Applying tags implies security relevance regardless of AI classification");
    }

    @Test
    void overrideYaml_existingEntryUpdatedInPlace(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of("security"), null,
                suggestion(true, List.of("security"), null, 0.0))), "");

        // Second write for the same fqcn#method must update the existing entry, not append.
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1,
                List.of("security", "auth"), null,
                suggestion(true, List.of("security", "auth"), null, 0.0))), "");

        var overrides = readOverrides(tempDir.resolve(".methodatlas/overrides.yaml"));
        assertEquals(1, overrides.size(),
                "Second save for the same method must update in place, not append");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) overrides.get(0).get("tags");
        assertEquals(List.of("security", "auth"), tags,
                "Updated entry must carry the newest tags");
    }

    @Test
    void overrideYaml_newEntryAppendedAlongsideExisting(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m1", 1, List.of("security"), null,
                suggestion(true, List.of("security"), null, 0.0))), "");
        AuditWriter.write(tempDir, List.of(entry("c", "m2", 1, List.of("security"), null,
                suggestion(true, List.of("security"), null, 0.0))), "");

        var overrides = readOverrides(tempDir.resolve(".methodatlas/overrides.yaml"));
        assertEquals(2, overrides.size(),
                "New fqcn#method must be appended alongside the existing entry");
    }

    @Test
    void overrideYaml_noteContainsTimestampAndOperator(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of("security"), null,
                suggestion(true, List.of("security"), null, 0.0))), "Jane Doe");

        var overrides = readOverrides(tempDir.resolve(".methodatlas/overrides.yaml"));
        String note = (String) overrides.get(0).get("note");
        assertTrue(note.startsWith("Reviewed "),
                "Note must begin with 'Reviewed '");
        assertTrue(note.endsWith("by Jane Doe"),
                "Note must end with the operator name when configured");
    }

    @Test
    void overrideYaml_noteOmitsOperatorWhenBlank(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of("security"), null,
                suggestion(true, List.of("security"), null, 0.0))), "  ");

        var overrides = readOverrides(tempDir.resolve(".methodatlas/overrides.yaml"));
        String note = (String) overrides.get(0).get("note");
        assertFalse(note.contains(" by "),
                "Blank operator name must not produce a 'by ...' suffix");
    }

    @Test
    void overrideYaml_displayNameOmittedWhenBlank(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of("security"), "  ",
                suggestion(true, List.of("security"), null, 0.0))), "");

        var overrides = readOverrides(tempDir.resolve(".methodatlas/overrides.yaml"));
        assertNull(overrides.get(0).get("displayName"),
                "Blank display name must not appear in the YAML output");
    }

    @Test
    void overrideYaml_reasonOmittedWhenNullOrBlank(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of("security"), null,
                suggestion(true, List.of("security"), "  ", 0.0))), "");

        var overrides = readOverrides(tempDir.resolve(".methodatlas/overrides.yaml"));
        assertNull(overrides.get(0).get("reason"),
                "Blank reason must not appear in the YAML output");
    }

    // ── Encoding ─────────────────────────────────────────────────────────────

    @Test
    void evidenceCsv_isUtf8Encoded(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "tëst_ünïcode", 1, List.of("security"), null,
                suggestion(true, List.of("security"), "héllo wörld", 0.0))), "");

        // Read back as UTF-8; the non-ASCII characters must round-trip.
        String csv = Files.readString(findEvidenceCsv(tempDir.resolve(".methodatlas")),
                StandardCharsets.UTF_8);
        assertTrue(csv.contains("tëst_ünïcode"),
                "Method name with non-ASCII characters must round-trip via UTF-8");
        assertTrue(csv.contains("héllo wörld"),
                "AI reason with non-ASCII characters must round-trip via UTF-8");
    }

    // ── Utility class invariants ─────────────────────────────────────────────

    @Test
    void auditWriter_isUtilityClass_cannotBeInstantiated() {
        // The constructor is private; reflective access throws after a
        // setAccessible(true) -- effectively un-callable from production code.
        // This test documents the design choice.
        var ctors = AuditWriter.class.getDeclaredConstructors();
        assertEquals(1, ctors.length,
                "Utility class should declare exactly one (private) constructor");
        assertFalse(java.lang.reflect.Modifier.isPublic(ctors[0].getModifiers()),
                "Utility constructor must not be public");
    }
}
