// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        return new SavedEntry(fqcn, method, loc, appliedTags, displayName, suggestion, null);
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
                        + "ai_interaction_score,ai_confidence,tag_ai_drift,tags_added,tags_removed,"
                        + "ai_taxonomy_version",
                firstLine,
                "Header must be the DeltaReport schema plus tags_added/tags_removed and, last, "
                        + "ai_taxonomy_version");
    }

    @Test
    void evidenceCsv_stampsTaxonomyVersionOnAiRowsOnly(@TempDir Path tempDir) throws IOException {
        SavedEntry withAi = new SavedEntry("c", "withAi", 5, List.of("security"), null,
                suggestion(true, List.of("security"), "r", 0.0), null, "built-in/default@1");
        SavedEntry withoutAi = new SavedEntry("c", "noAi", 5, List.of("security"), null,
                null, null, "built-in/default@1");
        SavedEntry unversioned = entry("c", "unversioned", 5, List.of("security"), null,
                suggestion(true, List.of("security"), "r", 0.0));

        AuditWriter.write(tempDir, List.of(withAi, withoutAi, unversioned), "");

        List<String> lines = Files.readAllLines(findEvidenceCsv(tempDir.resolve(".methodatlas")),
                StandardCharsets.UTF_8);
        assertTrue(lines.get(1).endsWith(",built-in/default@1"), lines.get(1));
        assertTrue(lines.get(2).endsWith(","), "row without AI suggestion must be blank: " + lines.get(2));
        assertFalse(lines.get(2).contains("built-in"), lines.get(2));
        assertTrue(lines.get(3).endsWith(","), "unversioned row must be blank: " + lines.get(3));
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

    // ── content_hash ─────────────────────────────────────────────────────────

    private static final String HASH_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final int COL_CONTENT_HASH = 5;

    private static SavedEntry hashed(String hash) {
        return new SavedEntry("c", "m", 1, List.of("security"), null, null, hash);
    }

    @Test
    void evidenceCsv_contentHashIsWrittenVerbatim(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(hashed(HASH_ABC)), "");

        assertEquals(HASH_ABC, dataFields(tempDir)[COL_CONTENT_HASH],
                "content_hash must carry the supplied per-class SHA-256");
    }

    @Test
    void evidenceCsv_contentHashBlankWhenSourceUnavailable(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(hashed(null)), "");

        assertEquals("", dataFields(tempDir)[COL_CONTENT_HASH],
                "content_hash must be blank when the class source was unavailable");
    }

    // ── SHA-256 manifest ─────────────────────────────────────────────────────

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static List<String> manifestLines(Path tempDir) throws IOException {
        return Files.readAllLines(tempDir.resolve(".methodatlas").resolve(AuditWriter.MANIFEST_FILE),
                StandardCharsets.UTF_8);
    }

    @Test
    void manifest_recordsCorrectSha256OfCsvAndOverrides(@TempDir Path tempDir) throws Exception {
        AuditWriter.write(tempDir, List.of(hashed(HASH_ABC)), "Jane");

        Path dir = tempDir.resolve(".methodatlas");
        Path csv = findEvidenceCsv(dir);
        List<String> expected = List.of(
                sha256(csv) + "  " + csv.getFileName(),
                sha256(dir.resolve("overrides.yaml")) + "  overrides.yaml");
        assertEquals(expected.stream().sorted(
                (a, b) -> a.substring(66).compareTo(b.substring(66))).toList(),
                manifestLines(tempDir),
                "Manifest must list sha256sum-style lines sorted by file name");
    }

    @Test
    void manifest_keepsOldCsvDigestAndRefreshesOverrides(@TempDir Path tempDir) throws Exception {
        java.time.ZoneId zone = java.time.ZoneId.of("UTC");
        AuditWriter.write(tempDir, List.of(hashed(HASH_ABC)), "",
                java.time.Clock.fixed(java.time.Instant.parse("2026-01-01T10:00:00Z"), zone));
        Path dir = tempDir.resolve(".methodatlas");
        Path first = dir.resolve("methodatlas-20260101-100000.csv");
        String firstDigest = sha256(first);

        // Tamper with the first CSV, then perform a second save.
        Files.writeString(first, "tampered", StandardCharsets.UTF_8);
        AuditWriter.write(tempDir, List.of(new SavedEntry("c", "m2", 1, List.of("security"),
                null, null, HASH_ABC)), "",
                java.time.Clock.fixed(java.time.Instant.parse("2026-01-01T10:00:05Z"), zone));

        List<String> lines = manifestLines(tempDir);
        assertEquals(3, lines.size());
        assertTrue(lines.contains(firstDigest + "  methodatlas-20260101-100000.csv"),
                "The recorded digest of an earlier CSV must not be recomputed, "
                        + "so the tampering stays detectable");
        assertTrue(lines.contains(sha256(dir.resolve("methodatlas-20260101-100005.csv"))
                + "  methodatlas-20260101-100005.csv"));
        assertTrue(lines.contains(sha256(dir.resolve("overrides.yaml")) + "  overrides.yaml"),
                "overrides.yaml is cumulative, so its digest is refreshed on every save");
    }

    @Test
    void manifest_ignoresMalformedExistingLines(@TempDir Path tempDir) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(".methodatlas"));
        Files.writeString(dir.resolve(AuditWriter.MANIFEST_FILE),
                "not a digest line\n" + "short  x.csv\n", StandardCharsets.UTF_8);

        AuditWriter.write(tempDir, List.of(hashed(HASH_ABC)), "");

        List<String> lines = manifestLines(tempDir);
        assertEquals(2, lines.size(), "Malformed lines must be dropped, new artefacts recorded");
        assertTrue(lines.stream().noneMatch(l -> l.contains("not a digest")));
    }

    @Test
    void evidenceCsv_usesLfLineEndingsOnly(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(hashed(HASH_ABC)), "");

        String csv = Files.readString(findEvidenceCsv(tempDir.resolve(".methodatlas")), StandardCharsets.UTF_8);

        assertFalse(csv.contains(String.valueOf((char) 13)), "evidence CSV must be LF-only, byte-compatible with the CLI CSV");
        assertTrue(csv.endsWith(String.valueOf((char) 10)));
    }

    @Test
    void manifest_stillRecordsCsvWhenOverrideYamlStepFails(@TempDir Path tempDir) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(".methodatlas"));
        Files.writeString(dir.resolve("overrides.yaml"), "overrides: [ unterminated", StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class,
                () -> AuditWriter.write(tempDir, List.of(hashed(HASH_ABC)), ""));

        Path csv = findEvidenceCsv(dir);
        assertTrue(manifestLines(tempDir).stream().anyMatch(l -> l.endsWith("  " + csv.getFileName())),
                "a CSV created before the YAML failure must not be orphaned outside the manifest");
    }

    @Test
    void write_warnsWhenOverridesEditedOutOfBandBetweenSaves(@TempDir Path tempDir) throws IOException {
        java.time.ZoneId zone = java.time.ZoneId.of("UTC");
        AuditWriter.write(tempDir, List.of(hashed(HASH_ABC)), "",
                java.time.Clock.fixed(java.time.Instant.parse("2026-01-01T10:00:00Z"), zone));
        Path yaml = tempDir.resolve(".methodatlas").resolve("overrides.yaml");
        Files.writeString(yaml, Files.readString(yaml, StandardCharsets.UTF_8) + "# edited by hand\n",
                StandardCharsets.UTF_8);

        List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(AuditWriter.class.getName());
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            AuditWriter.write(tempDir, List.of(hashed(HASH_ABC)), "",
                    java.time.Clock.fixed(java.time.Instant.parse("2026-01-01T10:00:05Z"), zone));
        } finally {
            logger.removeHandler(handler);
        }

        assertTrue(records.stream().anyMatch(r -> r.getLevel() == java.util.logging.Level.WARNING),
                "an out-of-band edit of overrides.yaml must be logged, not silently re-hashed");
    }

    @Test
    void write_doesNotWarnWhenOverridesUntouchedBetweenSaves(@TempDir Path tempDir) throws IOException {
        java.time.ZoneId zone = java.time.ZoneId.of("UTC");
        AuditWriter.write(tempDir, List.of(hashed(HASH_ABC)), "",
                java.time.Clock.fixed(java.time.Instant.parse("2026-01-01T10:00:00Z"), zone));

        List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(AuditWriter.class.getName());
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            AuditWriter.write(tempDir, List.of(hashed(HASH_ABC)), "",
                    java.time.Clock.fixed(java.time.Instant.parse("2026-01-01T10:00:05Z"), zone));
        } finally {
            logger.removeHandler(handler);
        }

        assertTrue(records.stream().noneMatch(r -> r.getLevel() == java.util.logging.Level.WARNING));
    }

    // ── Collision-proof file name ────────────────────────────────────────────

    private static final java.time.Clock FIXED = java.time.Clock.fixed(
            java.time.Instant.parse("2026-03-04T05:06:07Z"), java.time.ZoneId.of("UTC"));

    @Test
    void evidenceCsv_sameSecondWriteDoesNotOverwrite(@TempDir Path tempDir) throws IOException {
        AuditWriter.write(tempDir, List.of(entry("c", "first", 1, List.of(), null, null)), "", FIXED);
        AuditWriter.write(tempDir, List.of(entry("c", "second", 1, List.of(), null, null)), "", FIXED);
        AuditWriter.write(tempDir, List.of(entry("c", "third", 1, List.of(), null, null)), "", FIXED);

        Path dir = tempDir.resolve(".methodatlas");
        String base = "methodatlas-20260304-050607";
        assertTrue(Files.readString(dir.resolve(base + ".csv")).contains("c,first,"));
        assertTrue(Files.readString(dir.resolve(base + "-1.csv")).contains("c,second,"));
        assertTrue(Files.readString(dir.resolve(base + "-2.csv")).contains("c,third,"));
    }

    @Test
    void evidenceCsv_preexistingFileIsNeverReplaced(@TempDir Path tempDir) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(".methodatlas"));
        Path existing = dir.resolve("methodatlas-20260304-050607.csv");
        Files.writeString(existing, "precious audit record", StandardCharsets.UTF_8);

        AuditWriter.write(tempDir, List.of(entry("c", "m", 1, List.of(), null, null)), "", FIXED);

        assertEquals("precious audit record", Files.readString(existing));
        assertTrue(Files.exists(dir.resolve("methodatlas-20260304-050607-1.csv")));
    }

    @Test
    void evidenceCsv_failsWhenAllCollisionSuffixesTaken(@TempDir Path tempDir) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(".methodatlas"));
        String base = "methodatlas-20260304-050607";
        Files.createFile(dir.resolve(base + ".csv"));
        for (int i = 1; i <= 999; i++) {
            Files.createFile(dir.resolve(base + "-" + i + ".csv"));
        }

        assertThrows(IOException.class, () -> AuditWriter.write(tempDir,
                List.of(entry("c", "m", 1, List.of(), null, null)), "", FIXED));
    }

    @Test
    void schemaVersion_isThree() {
        assertEquals(3, AuditWriter.SCHEMA_VERSION);
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
