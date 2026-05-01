package org.egothor.methodatlas;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * @return {@code 0} on success, {@code 1} when the mismatch limit is
     *         exceeded or a fatal error occurs
     * @throws IOException if the CSV file or source files cannot be read or
     *                     written
     */
    public static int apply(Path csvFile, List<Path> roots,
            int mismatchLimit, List<SourcePatcher> patchers, PrintWriter log)
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

        // ── Step 3: compute mismatches (symmetric difference) ─────────────────
        Set<String> inCsvNotSource = new LinkedHashSet<>(desiredState.keySet());
        inCsvNotSource.removeAll(sourceKeys);

        Set<String> inSourceNotCsv = new LinkedHashSet<>(sourceKeys);
        inSourceNotCsv.removeAll(desiredState.keySet());

        int mismatchCount = inCsvNotSource.size() + inSourceNotCsv.size();

        // ── Step 4: enforce mismatch limit ────────────────────────────────────
        if (mismatchLimit >= 0 && mismatchCount >= mismatchLimit) {
            return reportMismatchesAndAbort(inCsvNotSource, inSourceNotCsv, mismatchCount, mismatchLimit, log);
        }

        // ── Step 5: warn about mismatches (no limit, or below limit) ──────────
        warnMismatches(inCsvNotSource, inSourceNotCsv);

        // ── Step 6: apply changes file by file ────────────────────────────────
        return applyFilesLoop(sourceIndex, desiredState, patchers, mismatchCount, log);
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

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static int applyFilesLoop(Map<Path, List<MethodKey>> sourceIndex,
            Map<String, ScanRecord> desiredState, List<SourcePatcher> patchers,
            int mismatchCount, PrintWriter log) {
        int modifiedFiles = 0;
        int totalChanges = 0;
        boolean hadErrors = false;

        for (Map.Entry<Path, List<MethodKey>> entry : sourceIndex.entrySet()) {
            Path path = entry.getKey();
            if (!hasRelevantMethod(entry.getValue(), desiredState)) {
                continue;
            }

            SourcePatcher patcher = patchers.stream()
                    .filter(p -> p.supports(path))
                    .findFirst().orElse(null);
            if (patcher == null) {
                continue;
            }

            // Build per-method desired state maps for this file
            Map<String, List<String>> tagsToApply = new LinkedHashMap<>();
            Map<String, String> displayNames = new LinkedHashMap<>();

            for (MethodKey mk : entry.getValue()) {
                String k = key(mk.fqcn(), mk.method());
                ScanRecord desired = desiredState.get(k);
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

            try {
                int changes = patcher.patch(path, tagsToApply, displayNames, log);
                if (changes > 0) {
                    modifiedFiles++;
                    totalChanges += changes;
                }
            } catch (IOException e) {
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING, "Cannot process: " + path, e);
                }
                hadErrors = true;
            }
        }

        log.println("Apply-tags-from-csv complete: " + totalChanges + " change(s) in "
                + modifiedFiles + " file(s); " + mismatchCount + " mismatch(es) skipped.");
        return hadErrors ? 1 : 0;
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

    private static String key(String fqcn, String method) {
        return fqcn + "::" + method;
    }

    /** Lightweight tuple holding a fully qualified class name and a method name. */
    private record MethodKey(String fqcn, String method) {
    }
}
