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
     * @return {@code 0} on success, {@code 1} when the mismatch limit is
     *         exceeded or a fatal error occurs
     * @throws IOException if the CSV file or source files cannot be read or
     *                     written
     */
    public static int apply(Path csvFile, List<Path> roots,
            int mismatchLimit, List<SourcePatcher> patchers, PrintWriter log, boolean verbose)
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
        return applyFilesLoop(sourceIndex, desiredState, patchers, mismatchCount, log, verbose);
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
            int mismatchCount, PrintWriter log, boolean verbose) {
        int modifiedFiles = 0;
        int totalChanges = 0;
        int skippedFiles = 0;
        boolean hadErrors = false;

        for (Map.Entry<Path, List<MethodKey>> entry : sourceIndex.entrySet()) {
            FileOutcome outcome = processFile(entry.getKey(), entry.getValue(),
                    desiredState, patchers, log, verbose);
            totalChanges += outcome.changes();
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

        // Capacity 224 comfortably covers the worst-case message:
        //   "Apply-tags-from-csv complete: <int> change(s) in <int> file(s);
        //    <int> mismatch(es) skipped. <int> file(s) skipped (no source
        //    write-back support for the language)."
        // which is ~165 chars including the integer placeholders.
        StringBuilder summary = new StringBuilder(224)
                .append("Apply-tags-from-csv complete: ")
                .append(totalChanges).append(" change(s) in ")
                .append(modifiedFiles).append(" file(s); ")
                .append(mismatchCount).append(" mismatch(es) skipped.");
        if (skippedFiles > 0) {
            summary.append(' ').append(skippedFiles)
                    .append(" file(s) skipped (no source write-back support for the language).");
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
     * @return the outcome (change count, language-skip flag, error flag)
     */
    private static FileOutcome processFile(Path path, List<MethodKey> methods,
            Map<String, ScanRecord> desiredState, List<SourcePatcher> patchers,
            PrintWriter log, boolean verbose) {
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
        for (MethodKey mk : methods) {
            ScanRecord desired = desiredState.get(key(mk.fqcn(), mk.method()));
            if (desired == null) {
                continue;
            }
            if (desired.tags() != null) {
                tagsToApply.put(mk.method(), desired.tags());
            }
            // null means the column was absent from the CSV (old format) — leave @DisplayName untouched.
            // "" means the column was present but empty — remove @DisplayName.
            if (desired.displayName() != null) {
                displayNames.put(mk.method(), desired.displayName());
            }
        }

        if (verbose) {
            log.println("[verbose] applying to " + path + ": " + tagsToApply.size()
                    + " method(s) with a desired tag-set, " + displayNames.size()
                    + " with a desired display-name");
        }
        try {
            int changes = patcher.patch(path, tagsToApply, displayNames, log);
            if (verbose) {
                log.println("[verbose]   -> " + changes + " change(s) written to " + path);
            }
            return FileOutcome.forChanges(changes);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Cannot process: " + path, e);
            }
            return FileOutcome.forError();
        }
    }

    /**
     * Outcome of patching one source file: the number of changes written, and
     * whether the file was skipped for lack of a {@link SourcePatcher} or failed
     * with an I/O error.
     *
     * @param changes number of annotation changes written (zero when none applied)
     * @param skipped {@code true} when skipped because no patcher supports the language
     * @param error   {@code true} when patching threw an {@link IOException}
     */
    private record FileOutcome(int changes, boolean skipped, boolean error) {
        private static final FileOutcome NONE = new FileOutcome(0, false, false);

        private static FileOutcome forSkip() {
            return new FileOutcome(0, true, false);
        }

        private static FileOutcome forError() {
            return new FileOutcome(0, false, true);
        }

        private static FileOutcome forChanges(int changes) {
            return new FileOutcome(changes, false, false);
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
