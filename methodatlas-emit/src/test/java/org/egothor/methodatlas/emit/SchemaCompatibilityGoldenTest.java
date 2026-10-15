// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.egothor.methodatlas.api.ScanRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden (pinned-schema) regression guard for the frozen audit schema.
 *
 * <p>
 * The MethodAtlas CSV scan schema and the {@code ClassificationOverride} YAML
 * field set are consumed by downstream compliance tooling and by the GUI's
 * {@code .methodatlas/overrides.yaml} audit trail. As stated in {@code CLAUDE.md}
 * and {@code docs/audit-trail.md}, renaming, reordering, retyping, or removing
 * any of these fields is a <strong>breaking change</strong> that must be made
 * deliberately: bump the schema version and update the documentation in the same
 * change.
 * </p>
 *
 * <p>
 * This test hard-codes the exact, ordered contract as it stands today and fails
 * the build on any silent drift. A failure here is not a bug in the test — it is
 * the guard doing its job. If the change to the schema is intentional, update the
 * {@code GOLDEN_*} constants below <em>and</em> the audit-format documentation in
 * the same commit, and treat it as a versioned schema change.
 * </p>
 *
 * <p>
 * Covers SABSA attribute BA-005 (Schema-Stable) / risk RO-006: the frozen audit
 * schema previously had no automated regression guard.
 * </p>
 */
@Tag("unit")
@Tag("schema")
class SchemaCompatibilityGoldenTest {

    /**
     * The exact, ordered full CSV column schema produced by {@link OutputEmitter}
     * when every optional column is enabled. {@link DeltaReport} parses files in
     * this dialect, resolving each column by name. Changing this list — order,
     * spelling, or membership — is a versioned schema change.
     */
    private static final List<String> GOLDEN_FULL_CSV_COLUMNS = List.of(
            "fqcn",
            "method",
            "loc",
            "tags",
            "display_name",
            "source_root",
            "content_hash",
            "ai_security_relevant",
            "ai_display_name",
            "ai_tags",
            "ai_reason",
            "ai_interaction_score",
            "ai_confidence",
            "tag_ai_drift",
            "tags_added",
            "tags_removed",
            "ai_cwe",
            "ai_taxonomy_version");

    /**
     * The exact set of CSV columns {@link DeltaReport} consumes (resolves by name
     * and maps onto a {@link ScanRecord} field). Columns outside this set are
     * ignored by the parser for forward compatibility; columns inside it are the
     * delta/audit contract and must keep their name and type.
     */
    private static final List<String> GOLDEN_DELTA_CONSUMED_COLUMNS = List.of(
            "fqcn",
            "method",
            "loc",
            "tags",
            "display_name",
            "content_hash",
            "ai_security_relevant",
            "ai_display_name",
            "ai_tags",
            "ai_reason",
            "ai_confidence",
            "ai_interaction_score",
            "tag_ai_drift",
            "ai_cwe");

    /**
     * The exact, ordered set of {@code ClassificationOverride} YAML field names,
     * as exposed through the public {@link ClassificationOverride.Entry} record.
     * These are the field names the YAML loader and the GUI audit writer agree on.
     */
    private static final List<String> GOLDEN_OVERRIDE_FIELDS = List.of(
            "fqcn",
            "method",
            "securityRelevant",
            "tags",
            "displayName",
            "reason",
            "note");

    // -------------------------------------------------------------------------
    // CSV column schema
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the full ordered CSV column schema is byte-for-byte the frozen golden contract")
    @Tag("positive")
    void fullCsvHeader_matchesGoldenContractExactly() {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            // Every optional column enabled so the complete schema is rendered.
            OutputEmitter emitter = new OutputEmitter(pw,
                    /* aiEnabled */ true, /* confidenceEnabled */ true,
                    /* contentHashEnabled */ true, /* driftDetect */ true,
                    /* emitSourceRoot */ true, /* cweEnabled */ true,
                    /* taxonomyVersion */ "built-in/default@1");
            emitter.emitCsvHeader(OutputMode.CSV);
        }

        String headerLine = sw.toString().split("\\R", -1)[0];
        List<String> actual = Arrays.asList(headerLine.split(",", -1));

        assertEquals(GOLDEN_FULL_CSV_COLUMNS, actual,
                "The CSV column schema changed. If this is deliberate, bump the schema version, "
                        + "update GOLDEN_FULL_CSV_COLUMNS and the audit-format docs in the same change.");
    }

    @Test
    @DisplayName("DeltaReport still resolves every frozen column by name onto its ScanRecord field")
    @Tag("positive")
    void deltaReport_consumesEveryGoldenColumnByName(@TempDir Path tmp) throws IOException {
        // One header carrying exactly the consumed columns, one fully populated row.
        String header = String.join(",", GOLDEN_DELTA_CONSUMED_COLUMNS);
        String row = "com.acme.FooTest,test_one,7,fast;slow,Checks login,abc123,"
                + "true,SECURITY: login,security;auth,verifies auth,0.8,0.6,ai-only,CWE-287;CWE-306";
        Path csv = tmp.resolve("scan.csv");
        Files.writeString(csv, header + "\n" + row + "\n", StandardCharsets.UTF_8);

        List<ScanRecord> records = DeltaReport.loadRecords(csv);
        assertEquals(1, records.size());
        ScanRecord r = records.get(0);

        // Each assertion pins one column name → ScanRecord field mapping; a rename,
        // reorder, or retype of any consumed column fails exactly one of these.
        assertEquals("com.acme.FooTest", r.fqcn(), "fqcn");
        assertEquals("test_one", r.method(), "method");
        assertEquals(7, r.loc(), "loc");
        assertEquals(List.of("fast", "slow"), r.tags(), "tags");
        assertEquals("Checks login", r.displayName(), "display_name");
        assertEquals("abc123", r.contentHash(), "content_hash");
        assertEquals(Boolean.TRUE, r.aiSecurityRelevant(), "ai_security_relevant");
        assertEquals("SECURITY: login", r.aiDisplayName(), "ai_display_name");
        assertEquals(List.of("security", "auth"), r.aiTags(), "ai_tags");
        assertEquals("verifies auth", r.aiReason(), "ai_reason");
        assertEquals(0.8, r.aiConfidence(), 1e-9, "ai_confidence");
        assertEquals(0.6, r.aiInteractionScore(), 1e-9, "ai_interaction_score");
        assertEquals("ai-only", r.tagAiDrift(), "tag_ai_drift");
        assertEquals(List.of("CWE-287", "CWE-306"), r.aiCwe(), "ai_cwe");
    }

    // -------------------------------------------------------------------------
    // ClassificationOverride YAML field schema
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ClassificationOverride.Entry exposes exactly the frozen, ordered field set")
    @Tag("positive")
    void overrideEntry_recordComponents_matchGoldenContract() {
        List<String> components = Arrays.stream(ClassificationOverride.Entry.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(GOLDEN_OVERRIDE_FIELDS, components,
                "The ClassificationOverride field set changed. If this is deliberate, bump the schema "
                        + "version, update GOLDEN_OVERRIDE_FIELDS and the audit-format docs in the same change.");
    }

    @Test
    @DisplayName("every frozen ClassificationOverride YAML field name still deserialises into its Entry field")
    @Tag("positive")
    void overrideYaml_everyGoldenFieldDeserialises(@TempDir Path tmp) throws IOException {
        // A single entry exercising every recognised YAML field name.
        Path file = tmp.resolve("overrides.yaml");
        Files.writeString(file, """
                overrides:
                  - fqcn: com.acme.CryptoTest
                    method: roundTrip_encryptDecrypt
                    securityRelevant: true
                    tags: [security, crypto]
                    displayName: "SECURITY: crypto round-trip"
                    reason: "verifies AES-GCM integrity"
                    note: "Reviewed 2026-04-24 by alice"
                """, StandardCharsets.UTF_8);

        List<ClassificationOverride.Entry> entries = ClassificationOverride.load(file).entries();
        assertEquals(1, entries.size());
        ClassificationOverride.Entry e = entries.get(0);

        assertEquals("com.acme.CryptoTest", e.fqcn(), "fqcn");
        assertEquals("roundTrip_encryptDecrypt", e.method(), "method");
        assertEquals(Boolean.TRUE, e.securityRelevant(), "securityRelevant");
        assertEquals(List.of("security", "crypto"), e.tags(), "tags");
        assertEquals("SECURITY: crypto round-trip", e.displayName(), "displayName");
        assertEquals("verifies AES-GCM integrity", e.reason(), "reason");
        assertEquals("Reviewed 2026-04-24 by alice", e.note(), "note");
    }

    @Test
    @DisplayName("the CSV schema has no accidental carriage return (LF-only audit contract)")
    @Tag("positive")
    void csvHeader_isLineFeedOnly() {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            new OutputEmitter(pw, true, true, true, true, true, true, "v1").emitCsvHeader(OutputMode.CSV);
        }
        assertFalse(sw.toString().contains("\r"),
                "The audit CSV must be LF-only so it is byte-identical across platforms");
    }
}
