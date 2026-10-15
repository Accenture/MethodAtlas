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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * <p>A third file, {@value #MANIFEST_FILE}, records the SHA-256 of every
 * evidence CSV and of {@code overrides.yaml} in {@code sha256sum} format so the
 * audit files are tamper-evident by comparison (hash only, no signature).
 * Evidence CSVs are created with create-new semantics; a same-second collision
 * yields a {@code -N} suffixed name instead of an overwrite.</p>
 *
 * <p><strong>Manifest trust model.</strong> The manifest is hash-only: it
 * proves that a listed file still has the bytes recorded at its last save, but
 * it does not cover itself and carries no signature, so anyone able to edit
 * the evidence can regenerate it. Anchor it outside the working tree (commit it,
 * or include it in a signed evidence pack) to make tampering detectable. Because
 * {@code overrides.yaml} is cumulative and mutable, an edit made outside the GUI
 * between two saves is detected by comparing its digest with the previous
 * manifest entry before the new save; a mismatch is logged at WARNING and the
 * save proceeds, so a silent out-of-band edit is never laundered unnoticed.</p>
 *
 * <p>Both files are written after source patches have been applied.  If
 * writing fails the caller should show a warning — the source patches are
 * already on disk and must not be rolled back.</p>
 *
 * <p><strong>Schema note ({@link #SCHEMA_VERSION} 2):</strong> the
 * {@code content_hash} column, blank in version 1, now carries the CLI
 * per-class SHA-256 content hash.</p>
 *
 * <p><strong>Schema note ({@link #SCHEMA_VERSION} 3):</strong> the new final
 * column {@code ai_taxonomy_version} records the taxonomy version under which
 * the AI suggestion was produced (same identifier as the CLI column of the same
 * name); it is blank for rows without an AI suggestion.</p>
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

    /**
     * Schema version of the GUI audit-trail artefacts. Version 2 populates the
     * previously blank {@code content_hash} column, adds
     * {@value #MANIFEST_FILE}, and makes evidence CSV names collision-proof.
     * Version 3 appends the {@code ai_taxonomy_version} column as the last
     * column. Existing column order and the {@code ClassificationOverride}
     * fields are unchanged from version 1.
     */
    public static final int SCHEMA_VERSION = 3;

    /** File name of the SHA-256 manifest inside {@code .methodatlas/}. */
    public static final String MANIFEST_FILE = "manifest.sha256";

    private static final Logger LOG = Logger.getLogger(AuditWriter.class.getName());

    private static final String OVERRIDES_FILE = "overrides.yaml";
    private static final String MANIFEST_SEPARATOR = "  ";
    private static final int SHA256_HEX_LENGTH = 64;
    private static final int MANIFEST_LINE_CAPACITY = 96;
    /** Highest {@code -N} suffix tried when an evidence CSV name is taken. */
    private static final int MAX_COLLISION_SUFFIX = 999;

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter NOTE_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Shared, thread-safe YAML mapper (immutable after build). */
    private static final ObjectMapper YAML_MAPPER = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .build();

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
     * @param contentHash        SHA-256 content hash of the enclosing class as
     *                           scanned, computed with the CLI hashing
     *                           ({@code ContentHasher.hashClass}); {@code null}
     *                           when the class source was unavailable, in which
     *                           case the {@code content_hash} column is blank
     * @param aiTaxonomyVersion  taxonomy version identifier under which
     *                           {@code suggestion} was produced; written to the
     *                           {@code ai_taxonomy_version} column only when
     *                           {@code suggestion} is non-{@code null}; may be
     *                           {@code null} (column blank)
     */
    public record SavedEntry(
            String fqcn,
            String method,
            int loc,
            List<String> appliedTags,
            String appliedDisplayName,
            AiMethodSuggestion suggestion,
            String contentHash,
            String aiTaxonomyVersion) {

        /**
         * Creates an entry without a taxonomy version (the
         * {@code ai_taxonomy_version} column is written blank).
         *
         * @param fqcn               fully-qualified class name
         * @param method             simple method name
         * @param loc                lines of code (0 when unavailable)
         * @param appliedTags        tags written to the source file
         * @param appliedDisplayName display name written to source, or {@code null}
         * @param suggestion         AI suggestion at save time, or {@code null}
         * @param contentHash        class content hash, or {@code null}
         */
        public SavedEntry(String fqcn, String method, int loc, List<String> appliedTags,
                String appliedDisplayName, AiMethodSuggestion suggestion, String contentHash) {
            this(fqcn, method, loc, appliedTags, appliedDisplayName, suggestion, contentHash, null);
        }
    }

    /**
     * Writes the evidence CSV, updates the override YAML and refreshes the
     * SHA-256 manifest for all supplied saved entries.
     *
     * <p>The {@code .methodatlas/} directory is created if it does not exist.
     * The evidence CSV receives a fresh timestamped name (never an existing
     * one, see {@link #write(Path, List, String, Clock)}); the override YAML is
     * merged in place; {@value #MANIFEST_FILE} then records the SHA-256 of both
     * artefacts.</p>
     *
     * @param scannedDir   root directory that was scanned; artefacts go into
     *                     its {@code .methodatlas/} subdirectory
     * @param entries      entries that were successfully patched, in discovery
     *                     order; must not be {@code null} or empty
     * @param operatorName reviewer identifier for the {@code note} field, or
     *                     an empty string to omit it
     * @throws IOException if any output file cannot be written
     */
    public static void write(Path scannedDir, List<SavedEntry> entries,
            String operatorName) throws IOException {
        write(scannedDir, entries, operatorName, Clock.systemDefaultZone());
    }

    /**
     * Clock-injectable variant of {@link #write(Path, List, String)}.
     *
     * <p>The evidence CSV is created with {@link StandardOpenOption#CREATE_NEW};
     * if a file of the same second already exists a {@code -N} counter suffix
     * ({@code methodatlas-YYYYMMDD-HHmmss-1.csv}, {@code -2}, ...) is used, so an
     * existing audit record is never overwritten.</p>
     *
     * @param scannedDir   root directory that was scanned
     * @param entries      entries that were successfully patched
     * @param operatorName reviewer identifier, or an empty string
     * @param clock        time source for the file name and the override note
     * @throws IOException if any output file cannot be written, or if all
     *                     {@value #MAX_COLLISION_SUFFIX} collision suffixes are taken
     */
    // PMD.AvoidCatchingGenericException: deliberately catches RuntimeException so the CSV
    // is recorded in the manifest and never orphaned, whatever the YAML step throws.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ static void write(Path scannedDir, List<SavedEntry> entries,
            String operatorName, Clock clock) throws IOException {
        Path auditDir = scannedDir.resolve(".methodatlas");
        Files.createDirectories(auditDir);
        LocalDateTime now = LocalDateTime.now(clock);
        warnIfOverridesEditedOutOfBand(auditDir);
        Path csvFile = writeEvidenceCsv(auditDir, entries, now);
        try {
            updateOverrideYaml(auditDir, entries, operatorName, now);
        } catch (IOException | RuntimeException e) {
            // The CSV already exists on disk; record it in the manifest even though the
            // YAML step failed, so it can never be orphaned outside the manifest.
            try {
                updateManifest(auditDir, csvFile);
            } catch (IOException manifestFailure) {
                e.addSuppressed(manifestFailure);
            }
            throw e;
        }
        updateManifest(auditDir, csvFile);
    }

    /**
     * Logs a warning when {@code overrides.yaml} no longer matches the digest in the
     * previous manifest, i.e. it was edited outside MethodAtlas since the last save.
     *
     * <p>Must run before the override YAML is rewritten, otherwise the comparison
     * would see this save's own changes.</p>
     *
     * @param dir the {@code .methodatlas/} directory
     * @throws IOException if the manifest or the YAML cannot be read
     */
    private static void warnIfOverridesEditedOutOfBand(Path dir) throws IOException {
        Path yaml = dir.resolve(OVERRIDES_FILE);
        String recorded = readManifest(dir.resolve(MANIFEST_FILE)).get(OVERRIDES_FILE);
        if (recorded == null || !Files.exists(yaml)) {
            return;
        }
        String actual = sha256Hex(yaml);
        if (!actual.equals(recorded) && LOG.isLoggable(Level.WARNING)) {
            LOG.log(Level.WARNING, "{0} changed outside MethodAtlas since the last save (manifest {1}, now {2});"
                    + " the manifest is refreshed with the new digest. Review the edit.",
                    new Object[] {yaml, recorded, actual});
        }
    }

    // ── Evidence CSV ──────────────────────────────────────────────────────

    /**
     * Atomically creates an empty evidence CSV that cannot replace an existing
     * file.
     *
     * @param dir       the {@code .methodatlas/} directory
     * @param timestamp time used for the file name
     * @return path of the newly created, empty file
     * @throws IOException if the file cannot be created or the suffix space is
     *                     exhausted
     */
    private static Path createEvidenceFile(Path dir, LocalDateTime timestamp) throws IOException {
        String base = "methodatlas-" + timestamp.format(FILE_TS);
        for (int attempt = 0; attempt <= MAX_COLLISION_SUFFIX; attempt++) {
            Path candidate = dir.resolve(base + (attempt == 0 ? "" : "-" + attempt) + ".csv");
            if (tryCreate(candidate)) {
                return candidate;
            }
        }
        throw new IOException("No free evidence file name for " + base + " in " + dir);
    }

    /**
     * Creates {@code file} only if it does not exist (create-new semantics).
     *
     * @param file file to create
     * @return {@code true} if created, {@code false} if it already existed
     * @throws IOException on any other I/O failure
     */
    private static boolean tryCreate(Path file) throws IOException {
        try {
            Files.createFile(file);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    @SuppressWarnings("PMD.NPathComplexity")
    private static Path writeEvidenceCsv(Path dir, List<SavedEntry> entries,
            LocalDateTime timestamp) throws IOException {
        Path csvFile = createEvidenceFile(dir, timestamp);
        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
            // Explicit LF (not println) so the file is byte-identical to the LF CLI CSV on every OS.
            w.print("fqcn,method,loc,tags,display_name,content_hash,"
                    + "ai_security_relevant,ai_display_name,ai_tags,ai_reason,"
                    + "ai_interaction_score,ai_confidence,tag_ai_drift,tags_added,tags_removed,ai_taxonomy_version\n");
            for (SavedEntry e : entries) {
                AiMethodSuggestion ai = e.suggestion();
                List<String> applied = e.appliedTags();
                w.print(
                        csv(e.fqcn()) + ","
                        + csv(e.method()) + ","
                        + (e.loc() > 0 ? e.loc() : "") + ","
                        + csv(applied != null ? String.join(";", applied) : "") + ","
                        + csv(e.appliedDisplayName() != null ? e.appliedDisplayName() : "") + ","
                        + csv(e.contentHash()) + ","
                        + (ai != null ? ai.securityRelevant() : "") + ","
                        + csv(ai != null && ai.displayName() != null ? ai.displayName() : "") + ","
                        + csv(ai != null && ai.tags() != null ? String.join(";", ai.tags()) : "") + ","
                        + csv(ai != null && ai.reason() != null ? ai.reason() : "") + ","
                        + (ai != null ? String.format(Locale.ROOT, "%.1f", ai.interactionScore()) : "") + ","
                        + (ai != null ? String.format(Locale.ROOT, "%.1f", ai.confidence()) : "") + ","
                        + driftValue(applied, ai) + ","
                        + csv(tagDelta(applied, ai, true)) + ","
                        + csv(tagDelta(applied, ai, false)) + ","
                        + csv(ai != null ? e.aiTaxonomyVersion() : "") + "\n");
            }
        }
        return csvFile;
    }

    // ── SHA-256 manifest ──────────────────────────────────────────────────

    /**
     * Refreshes {@value #MANIFEST_FILE} in the same {@code sha256sum}-compatible
     * format as the evidence-pack manifest ({@code <hex>  <filename>}, sorted by
     * file name).
     *
     * <p>Entries already recorded for earlier evidence CSVs are carried over
     * <em>unchanged</em>, never re-hashed, so a later edit of an old CSV stays
     * detectable; the CSV just written and the (mutable, cumulative)
     * {@code overrides.yaml} are hashed afresh.</p>
     *
     * @param dir     the {@code .methodatlas/} directory
     * @param csvFile evidence CSV written by this run
     * @throws IOException if an artefact cannot be read or the manifest written
     */
    private static void updateManifest(Path dir, Path csvFile) throws IOException {
        Path manifest = dir.resolve(MANIFEST_FILE);
        Map<String, String> digests = readManifest(manifest);
        for (Path artefact : List.of(csvFile, dir.resolve(OVERRIDES_FILE))) {
            // overrides.yaml may be absent when the YAML step failed on a first save.
            if (Files.exists(artefact)) {
                digests.put(artefact.getFileName().toString(), sha256Hex(artefact));
            }
        }
        StringBuilder out = new StringBuilder(digests.size() * MANIFEST_LINE_CAPACITY);
        digests.forEach((name, hash) ->
                out.append(hash).append(MANIFEST_SEPARATOR).append(name).append('\n'));
        Files.writeString(manifest, out, StandardCharsets.UTF_8);
    }

    /**
     * Parses an existing manifest into a mutable, name-sorted map; malformed lines
     * are ignored.
     *
     * @param manifest manifest path; need not exist
     * @return file name to lowercase-hex digest; empty when the manifest is absent
     * @throws IOException if the manifest exists but cannot be read
     */
    private static Map<String, String> readManifest(Path manifest) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        if (Files.exists(manifest)) {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (line.length() > SHA256_HEX_LENGTH + MANIFEST_SEPARATOR.length()
                        && line.startsWith(MANIFEST_SEPARATOR, SHA256_HEX_LENGTH)) {
                    digests.put(line.substring(SHA256_HEX_LENGTH + MANIFEST_SEPARATOR.length()),
                            line.substring(0, SHA256_HEX_LENGTH));
                }
            }
        }
        return digests;
    }

    /**
     * Computes the SHA-256 of a file's bytes as lowercase hexadecimal.
     *
     * @param file file to hash
     * @return 64-character lowercase hex digest
     * @throws IOException if the file cannot be read
     */
    private static String sha256Hex(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
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

        ObjectMapper mapper = YAML_MAPPER;

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
