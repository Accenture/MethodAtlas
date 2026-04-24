package org.egothor.methodatlas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Computes the difference between two MethodAtlas scan outputs.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * {@link #compute(Path, Path)} parses two MethodAtlas CSV files produced by
 * separate scan runs and returns a {@link DeltaResult} that enumerates every
 * test method that was added, removed, or modified between the runs. Unchanged
 * methods are counted but not listed individually.
 * </p>
 *
 * <h2>Method identity</h2>
 *
 * <p>
 * Two records are considered to represent the same method when their
 * {@code fqcn} and {@code method} columns match exactly. If a class or method
 * is renamed between scans, the old name appears as {@code REMOVED} and the
 * new name appears as {@code ADDED}. MethodAtlas does not attempt to track
 * renames.
 * </p>
 *
 * <h2>Comparable fields</h2>
 *
 * <p>
 * For each method present in both scans, the following fields are compared:
 * </p>
 *
 * <ul>
 * <li>{@code loc} — lines of code; always compared</li>
 * <li>{@code tags} — JUnit {@code @Tag} set; always compared (order-independent)</li>
 * <li>{@code content_hash} — source fingerprint; compared only when both records
 *     have a non-{@code null} value (i.e., both scans were run with
 *     {@code -content-hash}); a hash difference indicates the enclosing class
 *     source was edited</li>
 * <li>{@code ai_security_relevant} — compared only when both records carry a
 *     non-{@code null} value (both scans used {@code -ai})</li>
 * <li>{@code ai_tags} — compared only when both records carry a non-{@code null}
 *     value; comparison is order-independent</li>
 * </ul>
 *
 * <p>
 * Fields absent from either record (i.e., produced by scans with different flag
 * sets) are skipped so that a scan with {@code -content-hash} can be meaningfully
 * compared with one that did not use that flag.
 * </p>
 *
 * <h2>CSV format compatibility</h2>
 *
 * <p>
 * The parser handles the MethodAtlas CSV dialect (RFC 4180, comma-delimited,
 * double-quote escaping). {@code #}-prefixed comment lines emitted by
 * {@code -emit-metadata} are skipped; the {@code scan_timestamp} metadata value
 * is extracted and forwarded to {@link DeltaResult} for display. Blank lines
 * are ignored. Unknown column names are ignored, making the parser
 * forward-compatible with columns added in future versions.
 * </p>
 *
 * @see DeltaEntry
 * @see DeltaEmitter
 */
public final class DeltaReport {

    private static final char CSV_QUOTE = '"';
    private static final char CSV_COMMA = ',';

    private DeltaReport() {
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Computes the difference between two MethodAtlas scan CSV files.
     *
     * <p>
     * Both files must be readable and must contain at least a CSV header row
     * with {@code fqcn} and {@code method} columns. Empty files (no data rows)
     * are handled gracefully and produce only {@code ADDED} or {@code REMOVED}
     * entries as appropriate.
     * </p>
     *
     * @param beforeCsv path to the scan output from the earlier run
     * @param afterCsv  path to the scan output from the later run
     * @return delta result; never {@code null}
     * @throws IOException              if either file cannot be read
     * @throws IllegalArgumentException if a required column ({@code fqcn} or
     *                                  {@code method}) is absent from a file
     */
    public static DeltaResult compute(Path beforeCsv, Path afterCsv) throws IOException {
        ParsedCsv before = parseCsv(beforeCsv);
        ParsedCsv after = parseCsv(afterCsv);

        // Build key → record maps; LinkedHashMap preserves file order, which produces
        // stable output when two runs scan the same sources in the same order.
        Map<String, ScanRecord> beforeMap = buildMap(before.records());
        Map<String, ScanRecord> afterMap = buildMap(after.records());

        List<DeltaEntry> entries = new ArrayList<>();
        int unchanged = 0;

        // Pass 1: check every before record against after.
        for (Map.Entry<String, ScanRecord> e : beforeMap.entrySet()) {
            ScanRecord afterRecord = afterMap.get(e.getKey());
            if (afterRecord == null) {
                entries.add(DeltaEntry.removed(e.getValue()));
            } else {
                Set<String> changed = findChangedFields(e.getValue(), afterRecord);
                if (changed.isEmpty()) {
                    unchanged++;
                } else {
                    entries.add(DeltaEntry.modified(e.getValue(), afterRecord, changed));
                }
            }
        }

        // Pass 2: find records in after that are not in before (ADDED).
        for (Map.Entry<String, ScanRecord> e : afterMap.entrySet()) {
            if (!beforeMap.containsKey(e.getKey())) {
                entries.add(DeltaEntry.added(e.getValue()));
            }
        }

        // Sort by (fqcn, method) so all changes to a class are grouped together.
        entries.sort(Comparator
                .<DeltaEntry, String>comparing(e -> e.record().fqcn())
                .thenComparing(e -> e.record().method()));

        return new DeltaResult(
                beforeCsv, afterCsv,
                before.scanTimestamp(), after.scanTimestamp(),
                beforeMap.size(), afterMap.size(),
                countSecurityRelevant(before.records()),
                countSecurityRelevant(after.records()),
                Collections.unmodifiableList(entries),
                unchanged);
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * The aggregate result of comparing two MethodAtlas scan outputs.
     *
     * <p>
     * The {@link #entries()} list contains only changed methods (ADDED, REMOVED,
     * MODIFIED). Unchanged methods are represented only by the
     * {@link #unchangedCount()} counter to keep the report concise.
     * </p>
     *
     * @param beforePath               path to the <em>before</em> CSV file
     * @param afterPath                path to the <em>after</em> CSV file
     * @param beforeTimestamp          {@code scan_timestamp} metadata extracted
     *                                 from the <em>before</em> file, or
     *                                 {@code null} when the file was produced
     *                                 without {@code -emit-metadata}
     * @param afterTimestamp           {@code scan_timestamp} metadata extracted
     *                                 from the <em>after</em> file, or {@code null}
     * @param totalBefore              total number of test methods in the
     *                                 <em>before</em> scan
     * @param totalAfter               total number of test methods in the
     *                                 <em>after</em> scan
     * @param securityRelevantBefore   number of security-relevant methods in the
     *                                 <em>before</em> scan; {@code 0} when no AI
     *                                 columns were present
     * @param securityRelevantAfter    number of security-relevant methods in the
     *                                 <em>after</em> scan; {@code 0} when no AI
     *                                 columns were present
     * @param entries                  unmodifiable list of changed entries in
     *                                 (fqcn, method) order
     * @param unchangedCount           number of methods present in both scans
     *                                 with no detected differences
     */
    public record DeltaResult(
            Path beforePath,
            Path afterPath,
            String beforeTimestamp,
            String afterTimestamp,
            int totalBefore,
            int totalAfter,
            int securityRelevantBefore,
            int securityRelevantAfter,
            List<DeltaEntry> entries,
            int unchangedCount) {

        /** Returns the number of {@link DeltaEntry.ChangeType#ADDED} entries. */
        public int addedCount() {
            return (int) entries.stream()
                    .filter(e -> e.changeType() == DeltaEntry.ChangeType.ADDED).count();
        }

        /** Returns the number of {@link DeltaEntry.ChangeType#REMOVED} entries. */
        public int removedCount() {
            return (int) entries.stream()
                    .filter(e -> e.changeType() == DeltaEntry.ChangeType.REMOVED).count();
        }

        /** Returns the number of {@link DeltaEntry.ChangeType#MODIFIED} entries. */
        public int modifiedCount() {
            return (int) entries.stream()
                    .filter(e -> e.changeType() == DeltaEntry.ChangeType.MODIFIED).count();
        }
    }

    // -------------------------------------------------------------------------
    // CSV parsing — package-private for testing
    // -------------------------------------------------------------------------

    /**
     * Returns all scan records from the given MethodAtlas CSV file.
     *
     * <p>Used by {@link AiResultCache} to build an in-memory lookup from a previous
     * scan output without going through the full delta-comparison path.</p>
     *
     * @param csvPath path to a MethodAtlas CSV output file
     * @return unmodifiable list of parsed records; empty when the file has no data rows
     * @throws IOException if the file cannot be read
     */
    /* default */ static List<ScanRecord> loadRecords(Path csvPath) throws IOException {
        return parseCsv(csvPath).records();
    }

    /**
     * Parses one line of a MethodAtlas CSV file according to RFC 4180.
     *
     * <p>
     * Fields may be optionally enclosed in double quotes. A double-quote
     * character within a quoted field is escaped as two consecutive
     * double-quotes ({@code ""}). The delimiter is a comma. A line ending with
     * a comma produces a trailing empty field.
     * </p>
     *
     * @param line the raw CSV line to parse (no line terminator)
     * @return list of unescaped field values; never {@code null}
     */
    /* default */ static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        int pos = 0;

        while (pos < line.length()) {
            char c = line.charAt(pos);
            if (c == CSV_QUOTE) {
                pos = parseQuotedField(line, pos + 1, field);
                // pos now points one past the closing quote (or end of string)
            } else if (c == CSV_COMMA) {
                result.add(field.toString());
                field.setLength(0);
                pos++;
            } else {
                field.append(c);
                pos++;
            }
        }
        result.add(field.toString()); // always add the last (or only) field
        return result;
    }

    /**
     * Parses characters of a quoted CSV field starting just after the opening quote,
     * appending unescaped characters to {@code field}.
     *
     * @param line  the full CSV line
     * @param start position of the first character inside the quoted field
     * @param field buffer receiving unescaped field content
     * @return position of the character immediately after the closing quote,
     *         or {@code line.length()} when the line ends before a closing quote
     */
    private static int parseQuotedField(String line, int start, StringBuilder field) {
        int pos = start;
        while (pos < line.length()) {
            char c = line.charAt(pos);
            if (c == CSV_QUOTE) {
                if (pos + 1 < line.length() && line.charAt(pos + 1) == CSV_QUOTE) {
                    field.append(CSV_QUOTE);
                    pos += 2; // skip both quotes of the escape sequence
                } else {
                    return pos + 1; // closing quote consumed; return position after it
                }
            } else {
                field.append(c);
                pos++;
            }
        }
        return pos; // unterminated quoted field — treat end-of-line as closing
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Intermediate result of reading a CSV file. */
    private record ParsedCsv(List<ScanRecord> records, String scanTimestamp) {
    }

    private static ParsedCsv parseCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        String timestamp = null;
        List<String> dataLines = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith("#")) {
                if (line.startsWith("# scan_timestamp:")) {
                    timestamp = line.substring("# scan_timestamp:".length()).trim();
                }
            } else if (!line.isBlank()) {
                dataLines.add(line);
            }
        }

        if (dataLines.isEmpty()) {
            return new ParsedCsv(List.of(), timestamp);
        }

        // First non-comment, non-blank line is the CSV header.
        List<String> header = parseCsvLine(dataLines.get(0));
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            colIndex.put(header.get(i).trim(), i);
        }

        List<ScanRecord> records = new ArrayList<>(dataLines.size() - 1);
        for (int i = 1; i < dataLines.size(); i++) {
            List<String> fields = parseCsvLine(dataLines.get(i));
            records.add(toScanRecord(fields, colIndex));
        }

        return new ParsedCsv(Collections.unmodifiableList(records), timestamp);
    }

    private static ScanRecord toScanRecord(List<String> fields, Map<String, Integer> colIndex) {
        return new ScanRecord(
                requireField(fields, colIndex, "fqcn"),
                requireField(fields, colIndex, "method"),
                parseInt(getField(fields, colIndex, "loc"), 0),
                parseSemicolonList(getField(fields, colIndex, "tags")),
                getField(fields, colIndex, "content_hash"),
                parseBoolean(getField(fields, colIndex, "ai_security_relevant")).orElse(null),
                getField(fields, colIndex, "ai_display_name"),
                parseSemicolonListOrNull(fields, colIndex, "ai_tags"),
                getField(fields, colIndex, "ai_reason"),
                parseDouble(getField(fields, colIndex, "ai_confidence")),
                parseDouble(getField(fields, colIndex, "ai_interaction_score")),
                getField(fields, colIndex, "tag_ai_drift"));
    }

    private static Map<String, ScanRecord> buildMap(List<ScanRecord> records) {
        Map<String, ScanRecord> map = new LinkedHashMap<>(records.size() * 2);
        for (ScanRecord r : records) {
            map.put(key(r), r);
        }
        return map;
    }

    private static String key(ScanRecord r) {
        return r.fqcn() + "::" + r.method();
    }

    private static int countSecurityRelevant(List<ScanRecord> records) {
        return (int) records.stream()
                .filter(r -> Boolean.TRUE.equals(r.aiSecurityRelevant())).count();
    }

    /**
     * Returns the set of field names whose values differ between {@code before} and
     * {@code after}.
     *
     * <p>
     * A field is included in the result only when it is non-{@code null} in
     * <em>both</em> records, ensuring that differences caused by different scan
     * flag sets (e.g. one run with {@code -content-hash}, one without) are not
     * falsely reported as modifications.
     * </p>
     */
    @SuppressWarnings("PMD.NPathComplexity")
    private static Set<String> findChangedFields(ScanRecord before, ScanRecord after) {
        Set<String> changed = new LinkedHashSet<>();

        if (before.loc() != after.loc()) {
            changed.add("loc");
        }
        if (!tagsEqual(before.tags(), after.tags())) {
            changed.add("tags");
        }
        if (before.contentHash() != null && after.contentHash() != null
                && !before.contentHash().equals(after.contentHash())) {
            changed.add("source");
        }
        if (before.aiSecurityRelevant() != null && after.aiSecurityRelevant() != null
                && !before.aiSecurityRelevant().equals(after.aiSecurityRelevant())) {
            changed.add("ai_security_relevant");
        }
        if (before.aiTags() != null && after.aiTags() != null
                && !new HashSet<>(before.aiTags()).equals(new HashSet<>(after.aiTags()))) {
            changed.add("ai_tags");
        }
        if (before.aiInteractionScore() != null && after.aiInteractionScore() != null
                && !before.aiInteractionScore().equals(after.aiInteractionScore())) {
            changed.add("ai_interaction_score");
        }
        if (before.tagAiDrift() != null && after.tagAiDrift() != null
                && !before.tagAiDrift().equals(after.tagAiDrift())) {
            changed.add("tag_ai_drift");
        }

        return changed;
    }

    private static boolean tagsEqual(List<String> a, List<String> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
    }

    // -------------------------------------------------------------------------
    // Field extraction helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the field value at the named column, or {@code null} when the column
     * is absent or the value is empty.
     */
    private static String getField(List<String> fields, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null || idx >= fields.size()) {
            return null;
        }
        String val = fields.get(idx);
        return (val == null || val.isEmpty()) ? null : val;
    }

    /**
     * Returns the field value at the named column; throws when the column is absent
     * from the header.
     */
    private static String requireField(List<String> fields, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null) {
            throw new IllegalArgumentException("Required CSV column missing: " + col);
        }
        if (idx >= fields.size()) {
            return "";
        }
        String val = fields.get(idx);
        return val != null ? val : "";
    }

    private static int parseInt(String val, int defaultValue) {
        if (val == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Optional<Boolean> parseBoolean(String val) {
        if (val == null || val.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Boolean.parseBoolean(val.trim()));
    }

    private static Double parseDouble(String val) {
        if (val == null) {
            return null;
        }
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a semicolon-separated tag list. Returns an empty list for a blank
     * value; never {@code null}.
     */
    private static List<String> parseSemicolonList(String val) {
        if (val == null || val.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : val.split(";", -1)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Like {@link #parseSemicolonList(String)} but returns {@code null} when the
     * column is entirely absent from the file (versus present but empty). This
     * distinction matters for AI tag comparison: a column absent from the header
     * means AI was not run at all, while an empty column value means AI ran but
     * assigned no tags.
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private static List<String> parseSemicolonListOrNull(List<String> fields,
            Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null) {
            return null; // column absent — AI not run; null is semantically distinct from empty list
        }
        String val = idx < fields.size() ? fields.get(idx) : null;
        return parseSemicolonList(val);
    }
}
