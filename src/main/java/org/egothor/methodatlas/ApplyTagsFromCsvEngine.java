package org.egothor.methodatlas;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.egothor.methodatlas.api.ScanRecord;
import org.egothor.methodatlas.emit.DeltaReport;
import org.egothor.methodatlas.api.SourcePatcher;

/**
 * Engine that applies annotation changes to source files driven by a
 * reviewed MethodAtlas CSV export.
 *
 * <p>
 * The {@code -apply-tags-from-csv} workflow allows a security or development
 * team to review MethodAtlas output in a CSV file, adjust the {@code tags} and
 * {@code display_name} columns to reflect the desired annotation state, and
 * then replay those decisions back into the source code. The CSV acts as a
 * <em>desired-state specification</em>: after a successful run, re-running
 * MethodAtlas on the same source tree would reproduce an equivalent CSV.
 * </p>
 *
 * <h2>Invariant</h2>
 * <p>
 * The CSV must be complete — it must contain one row for every test method
 * currently present in the scanned source tree (matching the configured file
 * suffixes and test annotations). A method that appears in the source but not
 * in the CSV, or in the CSV but not in the source, constitutes a
 * <em>mismatch</em> and is reported as a warning. When a mismatch limit is
 * configured, the engine aborts without making any source changes if the
 * number of mismatches reaches or exceeds that limit.
 * </p>
 *
 * <h2>What is applied</h2>
 * <ul>
 *   <li>{@code tags} column — the exact set of {@code @Tag} annotations desired
 *       on the method; existing tags not in the list are removed, missing tags
 *       are added</li>
 *   <li>{@code display_name} column — the desired {@code @DisplayName} text;
 *       empty means "remove any existing {@code @DisplayName}"</li>
 * </ul>
 *
 * <h2>AI-column promotion (risky, opt-in)</h2>
 * <p>
 * <strong>Not recommended.</strong> When the caller passes {@code promoteAi},
 * the engine falls back to the AI-suggested {@code ai_tags} /
 * {@code ai_display_name} columns for any method whose curated {@code tags} /
 * {@code display_name} column is blank, and writes that raw AI suggestion into
 * source. Promotion is per-field and independent, and only fires when the
 * curated value is blank <em>and</em> a non-blank AI value is present.
 * </p>
 * <p>
 * This mode deliberately bypasses the human review step that the
 * apply-from-csv workflow exists to enforce — AI output reaches source without
 * validation. It should not be used unless the promotion has been rethought and
 * approved for the target environment. Every promoted value is counted and
 * reported on the completion summary line (and itemised under {@code -verbose})
 * so the run log records exactly which source writes originated from AI rather
 * than from a reviewed column.
 * </p>
 *
 * <h2>Mismatch handling</h2>
 * <p>
 * If {@code mismatchLimit} is {@code -1} (no limit), mismatches are logged as
 * warnings and the engine proceeds. If {@code mismatchLimit} is {@code >= 0},
 * the engine first counts mismatches and aborts with exit code {@code 1} when
 * the count is {@code >= mismatchLimit}. Setting the limit to {@code 1}
 * therefore causes any mismatch to abort the run without touching source files.
 * </p>
 *
 * <h2>Languages supported for write-back</h2>
 * <p>
 * Source write-back is only available for languages whose discovery plugin
 * ships a {@link SourcePatcher} implementation — currently
 * <strong>Java</strong> (JUnit&nbsp;5 / 4 / TestNG) and <strong>C#</strong>
 * (xUnit / NUnit / MSTest). Files whose language has no patcher are skipped
 * during the apply phase; a per-file notice is written to {@code log} and
 * the aggregate skip count is appended to the completion summary line.
 * These files do not appear in the source-method index built by
 * {@link SourcePatcher#discoverMethodsByClass(Path)} either, so rows in the
 * CSV that describe tests in unsupported languages will be reported as
 * mismatches.
 * </p>
 *
 * <p>
 * This class is a non-instantiable utility holder.
 * </p>
 *
 * @see SourcePatcher
 * @see MethodAtlasApp
 */
public final class ApplyTagsFromCsvEngine {

    private static final Logger LOG = Logger.getLogger(ApplyTagsFromCsvEngine.class.getName());

    private ApplyTagsFromCsvEngine() {
    }

    /**
     * Applies annotation changes from a reviewed CSV to source files.
     *
     * <p>
     * Source-method inventory (for mismatch detection) and source file write-back
     * are both delegated to the supplied {@link SourcePatcher} implementations via
     * {@link SourcePatcher#discoverMethodsByClass(java.nio.file.Path)} and
     * {@link SourcePatcher#patch(java.nio.file.Path, java.util.Map, java.util.Map,
     * java.io.PrintWriter)} respectively.
     * </p>
     *
     * @param csvFile         path to a MethodAtlas CSV produced with a previous
     *                        scan and reviewed by the team; must contain
     *                        {@code fqcn}, {@code method}, {@code tags}, and
     *                        {@code display_name} columns
     * @param roots           source root directories to scan for test files
     * @param mismatchLimit   maximum number of mismatches before aborting;
     *                        {@code -1} means no limit (warn and proceed)
     * @param patchers        list of configured {@link SourcePatcher} implementations
     * @param log             writer for progress and summary output
     * @param verbose         when {@code true}, print the CSV desired-state keys,
     *                        the keys discovered in the source tree, and the
     *                        key-by-key match result to {@code log}, so a run that
     *                        reports zero updates can be diagnosed (for example a
     *                        fully qualified class name or working-directory
     *                        mismatch between the CSV and the scanned source)
     * @param promoteAi       <strong>risky, not recommended</strong>: when
     *                        {@code true}, the engine falls back to the
     *                        {@code ai_tags} / {@code ai_display_name} columns for
     *                        any method whose curated {@code tags} /
     *                        {@code display_name} column is blank, writing the raw
     *                        AI suggestion into source without human review;
     *                        every promoted value is counted on the summary line
     *                        and itemised under {@code verbose}
     * @return {@code 0} on success, {@code 1} when the mismatch limit is
     *         exceeded or a fatal error occurs
     * @throws IOException if the CSV file or source files cannot be read or
     *                     written
     */
    public static int apply(Path csvFile, List<Path> roots,
            int mismatchLimit, List<SourcePatcher> patchers, PrintWriter log, boolean verbose,
            boolean promoteAi)
            throws IOException {

        // ── Step 1: load the desired-state CSV ────────────────────────────────
        List<ScanRecord> records = DeltaReport.loadRecords(csvFile);
        if (records.isEmpty()) {
            log.println("Apply-tags-from-csv: CSV file contains no records — nothing to apply.");
            return 0;
        }

        Map<String, ScanRecord> desiredState = new HashMap<>(records.size() * 2);
        for (ScanRecord r : records) {
            desiredState.put(key(r.fqcn(), r.method()), r);
        }

        if (verbose) {
            logVerboseHeader(log, csvFile, roots, desiredState);
            if (promoteAi) {
                log.println("[verbose] -promote-ai: ENABLED — blank tags/display_name "
                        + "will be filled from ai_tags/ai_display_name (unvalidated AI output)");
            }
        }

        // ── Step 2: scan source to build current method inventory ─────────────
        // Build a map from source file path → list of (fqcn, methodName) pairs
        // by asking each SourcePatcher to discover methods in supported files.
        Map<Path, List<MethodKey>> sourceIndex =
                buildSourceIndex(roots, patchers);

        Set<String> sourceKeys = new LinkedHashSet<>();
        for (List<MethodKey> methods : sourceIndex.values()) {
            for (MethodKey mk : methods) {
                sourceKeys.add(key(mk.fqcn(), mk.method()));
            }
        }

        if (verbose) {
            logVerboseSource(log, sourceIndex, sourceKeys);
        }

        // ── Step 3: compute mismatches (symmetric difference) ─────────────────
        Set<String> inCsvNotSource = new LinkedHashSet<>(desiredState.keySet());
        inCsvNotSource.removeAll(sourceKeys);

        Set<String> inSourceNotCsv = new LinkedHashSet<>(sourceKeys);
        inSourceNotCsv.removeAll(desiredState.keySet());

        int mismatchCount = inCsvNotSource.size() + inSourceNotCsv.size();

        if (verbose) {
            logVerboseMatch(log, desiredState.size(), inCsvNotSource, inSourceNotCsv);
        }

        // ── Step 4: enforce mismatch limit ────────────────────────────────────
        if (mismatchLimit >= 0 && mismatchCount >= mismatchLimit) {
            return reportMismatchesAndAbort(inCsvNotSource, inSourceNotCsv, mismatchCount, mismatchLimit, log);
        }

        // ── Step 5: warn about mismatches (no limit, or below limit) ──────────
        warnMismatches(inCsvNotSource, inSourceNotCsv);

        // ── Step 6: apply changes file by file ────────────────────────────────
        return applyFilesLoop(sourceIndex, desiredState, patchers, mismatchCount, log, verbose, promoteAi);
    }

    private static int reportMismatchesAndAbort(Set<String> inCsvNotSource, Set<String> inSourceNotCsv,
            int mismatchCount, int mismatchLimit, PrintWriter log) {
        for (String k : inCsvNotSource) {
            log.println("MISMATCH (in CSV, not in source): " + k);
        }
        for (String k : inSourceNotCsv) {
            log.println("MISMATCH (in source, not in CSV): " + k);
        }
        log.println("Apply-tags-from-csv aborted: " + mismatchCount
                + " mismatch(es) >= limit " + mismatchLimit + ". No source files were modified.");
        return 1;
    }

    private static void warnMismatches(Set<String> inCsvNotSource, Set<String> inSourceNotCsv) {
        if (LOG.isLoggable(Level.WARNING)) {
            for (String k : inCsvNotSource) {
                LOG.warning("Mismatch (in CSV, not found in source): " + k);
            }
            for (String k : inSourceNotCsv) {
                LOG.warning("Mismatch (in source, not present in CSV): " + k);
            }
        }
    }

    private static int applyFilesLoop(Map<Path, List<MethodKey>> sourceIndex,
            Map<String, ScanRecord> desiredState, List<SourcePatcher> patchers,
            int mismatchCount, PrintWriter log, boolean verbose, boolean promoteAi) {
        int modifiedFiles = 0;
        int totalChanges = 0;
        int skippedFiles = 0;
        int totalPromoted = 0;
        boolean hadErrors = false;

        for (Map.Entry<Path, List<MethodKey>> entry : sourceIndex.entrySet()) {
            FileOutcome outcome = processFile(entry.getKey(), entry.getValue(),
                    desiredState, patchers, log, verbose, promoteAi);
            totalChanges += outcome.changes();
            totalPromoted += outcome.promoted();
            if (outcome.changes() > 0) {
                modifiedFiles++;
            }
            if (outcome.skipped()) {
                skippedFiles++;
            }
            if (outcome.error()) {
                hadErrors = true;
            }
        }

        // Capacity 320 comfortably covers the worst-case message:
        //   "Apply-tags-from-csv complete: <int> change(s) in <int> file(s);
        //    <int> mismatch(es) skipped. <int> file(s) skipped (no source
        //    write-back support for the language). <int> value(s) promoted from
        //    AI columns (unvalidated — used with -promote-ai)."
        StringBuilder summary = new StringBuilder(320)
                .append("Apply-tags-from-csv complete: ")
                .append(totalChanges).append(" change(s) in ")
                .append(modifiedFiles).append(" file(s); ")
                .append(mismatchCount).append(" mismatch(es) skipped.");
        if (skippedFiles > 0) {
            summary.append(' ').append(skippedFiles)
                    .append(" file(s) skipped (no source write-back support for the language).");
        }
        if (promoteAi) {
            summary.append(' ').append(totalPromoted)
                    .append(" value(s) promoted from AI columns (unvalidated — used with -promote-ai).");
        }
        log.println(summary.toString());
        if (totalChanges == 0 && !verbose) {
            log.println("Hint: no methods were updated. Re-run with -verbose to print the CSV keys, "
                    + "the keys discovered in the source, and the key-by-key match result — the "
                    + "usual cause is a fully qualified class name or working-directory mismatch.");
        }
        return hadErrors ? 1 : 0;
    }

    /**
     * Applies the desired state to a single source file and reports the outcome.
     *
     * @param path         source file to patch
     * @param methods      methods discovered in {@code path}
     * @param desiredState CSV desired state keyed by {@code <fqcn>::<method>}
     * @param patchers     configured source patchers
     * @param log          progress writer
     * @param verbose      whether to print per-file diagnostics
     * @param promoteAi    whether to fall back to the AI columns for blank
     *                     curated values (risky; see the class Javadoc)
     * @return the outcome (change count, promotion count, language-skip flag,
     *         error flag)
     */
    private static FileOutcome processFile(Path path, List<MethodKey> methods,
            Map<String, ScanRecord> desiredState, List<SourcePatcher> patchers,
            PrintWriter log, boolean verbose, boolean promoteAi) {
        if (!hasRelevantMethod(methods, desiredState)) {
            if (verbose) {
                log.println("[verbose] no CSV row matches any method in " + path + " — skipping");
            }
            return FileOutcome.NONE;
        }

        SourcePatcher patcher = patchers.stream()
                .filter(p -> p.supports(path))
                .findFirst().orElse(null);
        if (patcher == null) {
            if (LOG.isLoggable(Level.INFO)) {
                LOG.log(Level.INFO, "Skipping {0}: no SourcePatcher available for this language", path);
            }
            log.println("Apply-tags-from-csv: skipped " + path
                    + " — source write-back is not supported for this language "
                    + "(currently Java and C# only)");
            return FileOutcome.forSkip();
        }

        Map<String, List<String>> tagsToApply = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        int promoted = collectDesiredState(path, methods, desiredState, promoteAi,
                tagsToApply, displayNames, log, verbose);

        if (verbose) {
            log.println("[verbose] applying to " + path + ": " + tagsToApply.size()
                    + " method(s) with a desired tag-set, " + displayNames.size()
                    + " with a desired display-name"
                    + (promoteAi ? "; " + promoted + " value(s) promoted from AI columns" : ""));
        }
        try {
            int changes = patcher.patch(path, tagsToApply, displayNames, log);
            if (verbose) {
                log.println("[verbose]   -> " + changes + " change(s) written to " + path);
            }
            return FileOutcome.forChanges(changes, promoted);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Cannot process: " + path, e);
            }
            return FileOutcome.forError();
        }
    }

    /**
     * Fills the {@code tagsToApply} and {@code displayNames} maps from the CSV
     * desired state for every method discovered in {@code path}, applying
     * AI-column promotion when {@code promoteAi} is set.
     *
     * <p>
     * The {@code displayNames} value carries the established three-way contract:
     * {@code null} (column absent) leaves {@code @DisplayName} untouched and is
     * therefore not put into the map; {@code ""} removes it; non-empty text sets
     * it. Promotion only substitutes an AI value when the curated value is blank
     * and a non-blank AI value is present, so the contract is preserved for
     * every method the operator curated by hand.
     * </p>
     *
     * @param path         source file being processed (used for verbose notices)
     * @param methods      methods discovered in {@code path}
     * @param desiredState CSV desired state keyed by {@code <fqcn>::<method>}
     * @param promoteAi    whether to fall back to the AI columns for blank values
     * @param tagsToApply  output map from method name to desired tag list
     * @param displayNames output map from method name to desired display name
     * @param log          progress writer for verbose per-promotion notices
     * @param verbose      whether to itemise each promotion
     * @return the number of values promoted from the AI columns
     */
    private static int collectDesiredState(Path path, List<MethodKey> methods,
            Map<String, ScanRecord> desiredState, boolean promoteAi,
            Map<String, List<String>> tagsToApply, Map<String, String> displayNames,
            PrintWriter log, boolean verbose) {
        int promoted = 0;
        for (MethodKey mk : methods) {
            ScanRecord desired = desiredState.get(key(mk.fqcn(), mk.method()));
            if (desired == null) {
                continue;
            }
            Resolved<List<String>> tags = resolveTags(desired, promoteAi);
            if (tags.value() != null) {
                tagsToApply.put(mk.method(), tags.value());
            }
            Resolved<String> name = resolveDisplayName(desired, promoteAi);
            if (name.value() != null) {
                displayNames.put(mk.method(), name.value());
            }
            if (tags.promoted()) {
                promoted++;
                logPromotion(log, verbose, path, mk, "tags <- ai_tags");
            }
            if (name.promoted()) {
                promoted++;
                logPromotion(log, verbose, path, mk, "display_name <- ai_display_name");
            }
        }
        return promoted;
    }

    /**
     * Resolves the desired tag list for a record, optionally promoting the
     * {@code ai_tags} column when the curated {@code tags} column is blank.
     *
     * @param record    CSV record for the method
     * @param promoteAi whether AI promotion is enabled
     * @return the resolved tag list and whether it came from the AI column
     */
    private static Resolved<List<String>> resolveTags(ScanRecord record, boolean promoteAi) {
        List<String> curated = record.tags();
        boolean curatedBlank = curated == null || curated.isEmpty();
        List<String> ai = record.aiTags();
        if (promoteAi && curatedBlank && ai != null && !ai.isEmpty()) {
            return new Resolved<>(ai, true);
        }
        return new Resolved<>(curated, false);
    }

    /**
     * Resolves the desired display name for a record, optionally promoting the
     * {@code ai_display_name} column when the curated {@code display_name} column
     * is blank.
     *
     * @param record    CSV record for the method
     * @param promoteAi whether AI promotion is enabled
     * @return the resolved display name and whether it came from the AI column
     */
    private static Resolved<String> resolveDisplayName(ScanRecord record, boolean promoteAi) {
        String curated = record.displayName();
        boolean curatedBlank = curated == null || curated.isBlank();
        String ai = record.aiDisplayName();
        if (promoteAi && curatedBlank && ai != null && !ai.isBlank()) {
            return new Resolved<>(ai, true);
        }
        return new Resolved<>(curated, false);
    }

    /**
     * Writes a per-promotion audit notice under {@code -verbose}.
     *
     * @param log     progress writer
     * @param verbose whether verbose output is enabled
     * @param path    source file being processed
     * @param mk      method whose value was promoted
     * @param detail  short description of the promoted field
     */
    private static void logPromotion(PrintWriter log, boolean verbose, Path path,
            MethodKey mk, String detail) {
        if (verbose) {
            log.println("[verbose]   [promote-ai] " + path + " " + mk.fqcn() + "::"
                    + mk.method() + " " + detail);
        }
    }

    /**
     * A resolved desired-state value paired with whether it was promoted from an
     * AI column rather than taken from a curated column.
     *
     * @param <T>      the value type ({@code List<String>} for tags,
     *                 {@code String} for display name)
     * @param value    the resolved value; may be {@code null} for display name
     *                 when the column was absent
     * @param promoted {@code true} when the value came from an AI column
     */
    private record Resolved<T>(T value, boolean promoted) {
    }

    /**
     * Outcome of patching one source file: the number of changes written, the
     * number of values promoted from AI columns, and whether the file was
     * skipped for lack of a {@link SourcePatcher} or failed with an I/O error.
     *
     * @param changes  number of annotation changes written (zero when none applied)
     * @param promoted number of desired-state values sourced from the AI columns
     * @param skipped  {@code true} when skipped because no patcher supports the language
     * @param error    {@code true} when patching threw an {@link IOException}
     */
    private record FileOutcome(int changes, int promoted, boolean skipped, boolean error) {
        private static final FileOutcome NONE = new FileOutcome(0, 0, false, false);

        private static FileOutcome forSkip() {
            return new FileOutcome(0, 0, true, false);
        }

        private static FileOutcome forError() {
            return new FileOutcome(0, 0, false, true);
        }

        private static FileOutcome forChanges(int changes, int promoted) {
            return new FileOutcome(changes, promoted, false, false);
        }
    }

    private static boolean hasRelevantMethod(List<MethodKey> methods, Map<String, ScanRecord> desiredState) {
        for (MethodKey mk : methods) {
            if (desiredState.containsKey(key(mk.fqcn(), mk.method()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans source roots and builds an index mapping each source file to the
     * list of (FQCN, method) pairs for all test methods it contains.
     *
     * <p>
     * Each supported source file is passed to
     * {@link SourcePatcher#discoverMethodsByClass(Path)} to obtain the actual
     * test-method inventory. This gives the correct FQCN–method pairs
     * regardless of whether the source tree uses the standard
     * {@code package/to/ClassName.java} directory structure.
     * </p>
     *
     * @param roots    source root directories
     * @param patchers list of configured patchers used to identify supported files
     * @return map from source file path to list of {@link MethodKey} entries;
     *         never {@code null}
     * @throws IOException if a file tree cannot be traversed
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static Map<Path, List<MethodKey>> buildSourceIndex(
            List<Path> roots,
            List<SourcePatcher> patchers) throws IOException {

        Map<Path, List<MethodKey>> index = new HashMap<>();

        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path path : (Iterable<Path>) stream
                        .filter(Files::isRegularFile)::iterator) {

                    SourcePatcher patcher = patchers.stream()
                            .filter(p -> p.supports(path))
                            .findFirst().orElse(null);
                    if (patcher == null) {
                        continue;
                    }

                    // Ask the patcher to discover test methods in this file
                    Map<String, List<String>> byClass = patcher.discoverMethodsByClass(path);
                    List<MethodKey> keys = new ArrayList<>();
                    for (Map.Entry<String, List<String>> classEntry : byClass.entrySet()) {
                        String fqcn = classEntry.getKey();
                        for (String methodName : classEntry.getValue()) {
                            keys.add(new MethodKey(fqcn, methodName));
                        }
                    }
                    if (!keys.isEmpty()) {
                        index.put(path, keys);
                    }
                }
            }
        }

        return index;
    }

    /**
     * Prints, under {@code -verbose}, the run context and the complete set of
     * desired-state keys loaded from the CSV.
     *
     * @param log          progress writer
     * @param csvFile      reviewed CSV path
     * @param roots        configured scan roots
     * @param desiredState CSV-derived desired state keyed by {@code <fqcn>::<method>}
     */
    private static void logVerboseHeader(PrintWriter log, Path csvFile, List<Path> roots,
            Map<String, ScanRecord> desiredState) {
        log.println("[verbose] working directory: " + Paths.get("").toAbsolutePath());
        log.println("[verbose] CSV file: " + csvFile.toAbsolutePath());
        for (Path root : roots) {
            String note = Files.isDirectory(root) ? "" : "  (NOT an existing directory)";
            log.println("[verbose] scan root: " + root.toAbsolutePath() + note);
        }
        log.println("[verbose] CSV desired-state keys (" + desiredState.size()
                + "); lookup format is <fqcn>::<method>:");
        desiredState.keySet().stream().sorted()
                .forEach(k -> log.println("[verbose]   CSV  " + k));
    }

    /**
     * Prints, under {@code -verbose}, the keys discovered in the scanned source
     * tree (the right-hand side of the lookup).
     *
     * @param log         progress writer
     * @param sourceIndex source file to discovered methods
     * @param sourceKeys  flattened {@code <fqcn>::<method>} keys discovered in source
     */
    private static void logVerboseSource(PrintWriter log, Map<Path, List<MethodKey>> sourceIndex,
            Set<String> sourceKeys) {
        log.println("[verbose] source files with discoverable test methods: " + sourceIndex.size());
        log.println("[verbose] source keys (" + sourceKeys.size() + "):");
        sourceKeys.stream().sorted().forEach(k -> log.println("[verbose]   SRC  " + k));
        if (sourceKeys.isEmpty()) {
            log.println("[verbose] No test methods were discovered under the scan root(s). "
                    + "Confirm the command is run from the correct directory and that the files "
                    + "use a language with source write-back support (Java, C#).");
        }
    }

    /**
     * Prints, under {@code -verbose}, the key-by-key match result so an operator
     * can see exactly which CSV rows failed to line up with a source method.
     *
     * @param log             progress writer
     * @param desiredCount    number of CSV desired-state keys
     * @param inCsvNotSource  keys present in the CSV but absent from source
     * @param inSourceNotCsv  keys present in source but absent from the CSV
     */
    private static void logVerboseMatch(PrintWriter log, int desiredCount,
            Set<String> inCsvNotSource, Set<String> inSourceNotCsv) {
        log.println("[verbose] matched keys (present in both CSV and source): "
                + (desiredCount - inCsvNotSource.size()));
        log.println("[verbose] in CSV but NOT found in source (" + inCsvNotSource.size() + "):");
        inCsvNotSource.stream().sorted().forEach(k -> log.println("[verbose]   CSV-only  " + k));
        log.println("[verbose] in source but NOT present in CSV (" + inSourceNotCsv.size() + "):");
        inSourceNotCsv.stream().sorted().forEach(k -> log.println("[verbose]   SRC-only  " + k));
    }

    private static String key(String fqcn, String method) {
        return fqcn + "::" + method;
    }

    /** Lightweight tuple holding a fully qualified class name and a method name. */
    private record MethodKey(String fqcn, String method) {
    }
}
