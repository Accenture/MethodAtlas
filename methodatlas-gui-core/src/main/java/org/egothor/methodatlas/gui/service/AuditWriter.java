package org.egothor.methodatlas.gui.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.emit.TagAiDrift;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes audit evidence after a batch "Save All Changes" operation.
 *
 * <p>Two artefacts are produced in the hidden {@code .methodatlas/}
 * subdirectory of the scanned project root:</p>
 *
 * <ol>
 *   <li><strong>Timestamped evidence CSV</strong> —
 *       {@code methodatlas-YYYYMMDD-HHmmss.csv}.  One row per saved method.
 *       The first thirteen columns are the CLI {@code DeltaReport} schema, so
 *       existing tooling (diff, import, comparison) works without modification
 *       — {@code DeltaReport} resolves columns by name and ignores any it does
 *       not recognise.  Two extra columns, {@code tags_added} and
 *       {@code tags_removed}, record the reviewer's tag changes relative to the
 *       AI suggestion.  The file is never overwritten; each Save All creates a
 *       new timestamped file, giving an append-only audit trail.</li>
 *   <li><strong>Cumulative override YAML</strong> —
 *       {@code overrides.yaml}.  Uses the {@code ClassificationOverride} YAML
 *       format consumed by the CLI {@code -override-file} flag so that future
 *       analysis runs can reproduce the same tag decisions without re-invoking
 *       the AI.  Existing entries are updated in place; new entries are
 *       appended.  The {@code note} field carries the operator name (when
 *       configured in settings) and the ISO-8601 review timestamp.</li>
 * </ol>
 *
 * <p>Both files are written after source patches have been applied.  If
 * writing fails the caller should show a warning — the source patches are
 * already on disk and must not be rolled back.</p>
 *
 * <p><strong>Schema note (4.0.0):</strong> the {@code tag_ai_drift} column now
 * follows the same definition as the CLI, {@link TagAiDrift} — agreement
 * between a source-level {@code @Tag("security")} marker and the AI
 * security-relevance verdict — rather than a comparison of the whole applied
 * and AI tag sets. The reviewer's set changes moved to the dedicated
 * {@code tags_added}/{@code tags_removed} columns. See the Migration Guide.</p>
 *
 * @see org.egothor.methodatlas.gui.model.AppSettings#getOperatorName()
 * @see TagAiDrift
 */
public final class AuditWriter {

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter NOTE_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private AuditWriter() {}

    /**
     * Immutable snapshot of one saved method, captured before the staged patch
     * is cleared.
     *
     * @param fqcn               fully-qualified class name
     * @param method             simple method name
     * @param loc                lines of code (0 when unavailable)
     * @param appliedTags        tags written to the source file
     * @param appliedDisplayName display name written to source, or {@code null}
     * @param suggestion         AI suggestion at save time, or {@code null}
     */
    public record SavedEntry(
            String fqcn,
            String method,
            int loc,
            List<String> appliedTags,
            String appliedDisplayName,
            AiMethodSuggestion suggestion) {}

    /**
     * Writes the evidence CSV and updates the override YAML for all supplied
     * saved entries.
     *
     * <p>The {@code .methodatlas/} directory is created if it does not exist.
     * The evidence CSV receives a fresh timestamped name; the override YAML is
     * merged in place.</p>
     *
     * @param scannedDir   root directory that was scanned; artefacts go into
     *                     its {@code .methodatlas/} subdirectory
     * @param entries      entries that were successfully patched, in discovery
     *                     order; must not be {@code null} or empty
     * @param operatorName reviewer identifier for the {@code note} field, or
     *                     an empty string to omit it
     * @throws IOException if either output file cannot be written
     */
    public static void write(Path scannedDir, List<SavedEntry> entries,
            String operatorName) throws IOException {
        Path auditDir = scannedDir.resolve(".methodatlas");
        Files.createDirectories(auditDir);
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        writeEvidenceCsv(auditDir, entries, now);
        updateOverrideYaml(auditDir, entries, operatorName, now);
    }

    // ── Evidence CSV ──────────────────────────────────────────────────────

    @SuppressWarnings("PMD.NPathComplexity")
    private static void writeEvidenceCsv(Path dir, List<SavedEntry> entries,
            LocalDateTime timestamp) throws IOException {
        Path csvFile = dir.resolve("methodatlas-" + timestamp.format(FILE_TS) + ".csv");
        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8))) {
            w.println("fqcn,method,loc,tags,display_name,content_hash,"
                    + "ai_security_relevant,ai_display_name,ai_tags,ai_reason,"
                    + "ai_interaction_score,ai_confidence,tag_ai_drift,tags_added,tags_removed");
            for (SavedEntry e : entries) {
                AiMethodSuggestion ai = e.suggestion();
                List<String> applied = e.appliedTags();
                w.println(
                        csv(e.fqcn()) + ","
                        + csv(e.method()) + ","
                        + (e.loc() > 0 ? e.loc() : "") + ","
                        + csv(applied != null ? String.join(";", applied) : "") + ","
                        + csv(e.appliedDisplayName() != null ? e.appliedDisplayName() : "") + ","
                        + /* content_hash */ ","
                        + (ai != null ? ai.securityRelevant() : "") + ","
                        + csv(ai != null && ai.displayName() != null ? ai.displayName() : "") + ","
                        + csv(ai != null && ai.tags() != null ? String.join(";", ai.tags()) : "") + ","
                        + csv(ai != null && ai.reason() != null ? ai.reason() : "") + ","
                        + (ai != null ? String.format("%.1f", ai.interactionScore()) : "") + ","
                        + (ai != null ? String.format("%.1f", ai.confidence()) : "") + ","
                        + driftValue(applied, ai) + ","
                        + csv(tagDelta(applied, ai, true)) + ","
                        + csv(tagDelta(applied, ai, false)));
            }
        }
    }

    // ── Override YAML ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> loadExistingOverrides(
            Path yamlFile, ObjectMapper mapper) throws IOException {
        List<Map<String, Object>> existing = new ArrayList<>();
        if (!Files.exists(yamlFile)) { return existing; }
        Map<String, Object> root = mapper.readValue(yamlFile.toFile(), Map.class);
        if (root == null || !(root.get("overrides") instanceof List<?> list)) { return existing; }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                existing.add(new LinkedHashMap<>((Map<String, Object>) m));
            }
        }
        return existing;
    }

    @SuppressWarnings({"PMD.NPathComplexity", "PMD.AvoidInstantiatingObjectsInLoops"})
    private static void updateOverrideYaml(Path dir, List<SavedEntry> entries,
            String operatorName, LocalDateTime timestamp) throws IOException {
        Path yamlFile = dir.resolve("overrides.yaml");

        ObjectMapper mapper = YAMLMapper.builder()
                .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                .build();

        // Load existing overrides (if any) into a mutable list
        List<Map<String, Object>> existing = loadExistingOverrides(yamlFile, mapper);

        // Build an index keyed by "fqcn#method" for O(1) lookup
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < existing.size(); i++) {
            Map<String, Object> entry = existing.get(i);
            idx.put(entry.get("fqcn") + "#" + entry.get("method"), i);
        }

        String note = buildNote(operatorName, timestamp);

        for (SavedEntry e : entries) {
            AiMethodSuggestion ai = e.suggestion();
            List<String> applied = e.appliedTags();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fqcn", e.fqcn());
            entry.put("method", e.method());
            // Applying tags implies security relevance; preserve AI classification otherwise
            boolean secRel = (applied != null && !applied.isEmpty())
                    || (ai != null && ai.securityRelevant());
            entry.put("securityRelevant", secRel);
            if (applied != null && !applied.isEmpty()) {
                entry.put("tags", new ArrayList<>(applied));
            }
            if (e.appliedDisplayName() != null && !e.appliedDisplayName().isBlank()) {
                entry.put("displayName", e.appliedDisplayName());
            }
            if (ai != null && ai.reason() != null && !ai.reason().isBlank()) {
                entry.put("reason", ai.reason());
            }
            entry.put("note", note);

            String key = e.fqcn() + "#" + e.method();
            Integer existingIdx = idx.get(key);
            if (existingIdx != null) {
                existing.set(existingIdx, entry);
            } else {
                idx.put(key, existing.size());
                existing.add(entry);
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("overrides", existing);
        mapper.writerWithDefaultPrettyPrinter().writeValue(yamlFile.toFile(), root);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String buildNote(String operatorName, LocalDateTime timestamp) {
        String ts = timestamp.format(NOTE_TS);
        if (operatorName != null && !operatorName.isBlank()) {
            return "Reviewed " + ts + " by " + operatorName.trim();
        }
        return "Reviewed " + ts;
    }

    /**
     * Computes the {@code tag_ai_drift} value using the same definition as the
     * CLI ({@link TagAiDrift}): the agreement between the applied
     * {@code @Tag("security")} marker and the AI security-relevance verdict.
     *
     * @param applied tags applied to the method, or {@code null}
     * @param ai      AI suggestion, or {@code null} when AI was not run
     * @return {@code none}, {@code tag-only}, or {@code ai-only}; empty string
     *         when there is no AI suggestion to compare against
     */
    private static String driftValue(List<String> applied, AiMethodSuggestion ai) {
        if (ai == null) {
            return "";
        }
        TagAiDrift drift = TagAiDrift.compute(applied != null ? applied : List.of(), ai);
        return drift != null ? drift.toValue() : "";
    }

    /**
     * Computes the reviewer's tag changes relative to the AI suggestion, sorted
     * and joined with {@code ;} for a stable, comparable audit record.
     *
     * @param applied tags applied to the method, or {@code null}
     * @param ai      AI suggestion, or {@code null} when AI was not run
     * @param added   {@code true} for tags the reviewer added beyond the AI
     *                suggestion; {@code false} for tags the AI suggested that the
     *                reviewer did not keep
     * @return sorted, semicolon-joined tag names; empty string when there is no
     *         AI suggestion to diff against
     */
    private static String tagDelta(List<String> applied, AiMethodSuggestion ai, boolean added) {
        if (ai == null) {
            return "";
        }
        List<String> aiTags = ai.tags();
        return added ? TagAiDrift.tagDifference(applied, aiTags)
                : TagAiDrift.tagDifference(aiTags, applied);
    }

    /**
     * RFC 4180 CSV field quoting, with the same spreadsheet formula-injection
     * guard as the CLI emitter: a value whose first character is {@code =},
     * {@code +}, {@code -}, or {@code @} is quoted so a spreadsheet does not
     * evaluate it as a formula.
     *
     * @param value field value; may be {@code null}
     * @return CSV-safe representation; empty string for {@code null}
     */
    private static String csv(String value) {
        if (value == null || value.isEmpty()) { return ""; }
        boolean formulaPrefix = "=+-@".indexOf(value.charAt(0)) >= 0;
        if (formulaPrefix || value.contains(",") || value.contains("\"") || value.contains("\n")
                || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
