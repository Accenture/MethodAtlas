package org.egothor.methodatlas.gui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.egothor.methodatlas.ai.AiMethodSuggestion;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes audit evidence after a batch "Save All Changes" operation.
 *
 * <p>Two artefacts are produced in the hidden {@code .methodatlas/}
 * subdirectory of the scanned project root:</p>
 *
 * <ol>
 *   <li><strong>Timestamped evidence CSV</strong> —
 *       {@code methodatlas-YYYYMMDD-HHmmss.csv}.  One row per saved method,
 *       using the same column schema as the CLI {@code DeltaReport} CSV so
 *       that existing tooling (diff, import, comparison) works without
 *       modification.  The file is never overwritten; each Save All creates a
 *       new timestamped file, giving an append-only audit trail.</li>
 *   <li><strong>Cumulative override YAML</strong> —
 *       {@code overrides.yaml}.  Uses the {@code ClassificationOverride} YAML
 *       format consumed by the CLI {@code --override} flag so that future
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
 * @see org.egothor.methodatlas.gui.model.AppSettings#getOperatorName()
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
        LocalDateTime now = LocalDateTime.now();
        writeEvidenceCsv(auditDir, entries, now);
        updateOverrideYaml(auditDir, entries, operatorName, now);
    }

    // ── Evidence CSV ──────────────────────────────────────────────────────

    private static void writeEvidenceCsv(Path dir, List<SavedEntry> entries,
            LocalDateTime timestamp) throws IOException {
        Path csvFile = dir.resolve("methodatlas-" + timestamp.format(FILE_TS) + ".csv");
        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8))) {
            w.println("fqcn,method,loc,tags,display_name,content_hash,"
                    + "ai_security_relevant,ai_display_name,ai_tags,ai_reason,"
                    + "ai_confidence,ai_interaction_score,tag_ai_drift");
            for (SavedEntry e : entries) {
                AiMethodSuggestion ai = e.suggestion();
                List<String> applied = e.appliedTags();
                w.println(
                        csv(e.fqcn()) + ","
                        + csv(e.method()) + ","
                        + (e.loc() > 0 ? e.loc() : "") + ","
                        + csv(applied != null ? String.join(";", applied) : "") + ","
                        + csv(e.appliedDisplayName() != null ? e.appliedDisplayName() : "") + ","
                        + /* content_hash */ "" + ","
                        + (ai != null ? ai.securityRelevant() : "") + ","
                        + csv(ai != null && ai.displayName() != null ? ai.displayName() : "") + ","
                        + csv(ai != null && ai.tags() != null ? String.join(";", ai.tags()) : "") + ","
                        + csv(ai != null && ai.reason() != null ? ai.reason() : "") + ","
                        + (ai != null ? ai.confidence() : "") + ","
                        + (ai != null ? ai.interactionScore() : "") + ","
                        + computeDrift(applied, ai != null ? ai.tags() : null));
            }
        }
    }

    // ── Override YAML ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void updateOverrideYaml(Path dir, List<SavedEntry> entries,
            String operatorName, LocalDateTime timestamp) throws IOException {
        Path yamlFile = dir.resolve("overrides.yaml");

        YAMLFactory yf = new YAMLFactory()
                .configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false);
        ObjectMapper mapper = new ObjectMapper(yf);

        // Load existing overrides (if any) into a mutable list
        List<Map<String, Object>> existing = new ArrayList<>();
        if (Files.exists(yamlFile)) {
            Map<String, Object> root = mapper.readValue(yamlFile.toFile(), Map.class);
            if (root != null && root.get("overrides") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        existing.add(new LinkedHashMap<>((Map<String, Object>) m));
                    }
                }
            }
        }

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

    private static String computeDrift(List<String> applied, List<String> aiTags) {
        if (applied == null) return "";
        Set<String> appliedSet = new HashSet<>(applied);
        Set<String> aiSet = aiTags != null ? new HashSet<>(aiTags) : Set.of();
        boolean userAddedExtra = applied.stream().anyMatch(t -> !aiSet.contains(t));
        boolean aiHasUnapplied = aiSet.stream().anyMatch(t -> !appliedSet.contains(t));
        if (!userAddedExtra && !aiHasUnapplied) return "none";
        if (!userAddedExtra) return "ai-only";
        return "tag-only";
    }

    /** RFC 4180 CSV field quoting. */
    private static String csv(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")
                || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
