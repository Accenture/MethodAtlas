package org.egothor.methodatlas;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/**
 * Engine that applies annotation changes to Java source files driven by a
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
 * The CSV must be complete — it must contain one row for every JUnit test
 * method currently present in the scanned source tree (matching the configured
 * file suffixes and test annotations). A method that appears in the source but
 * not in the CSV, or in the CSV but not in the source, constitutes a
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
 * @see TagApplier
 * @see MethodAtlasApp
 */
final class ApplyTagsFromCsvEngine {

    private static final Logger LOG = Logger.getLogger(ApplyTagsFromCsvEngine.class.getName());

    private ApplyTagsFromCsvEngine() {
    }

    /**
     * Applies annotation changes from a reviewed CSV to source files.
     *
     * @param csvFile         path to a MethodAtlas CSV produced with a previous
     *                        scan and reviewed by the team; must contain
     *                        {@code fqcn}, {@code method}, {@code tags}, and
     *                        {@code display_name} columns
     * @param roots           source root directories to scan for test files
     * @param fileSuffixes    filename suffixes used to identify test source files
     * @param testAnnotations annotation simple names that identify test methods
     * @param mismatchLimit   maximum number of mismatches before aborting;
     *                        {@code -1} means no limit (warn and proceed)
     * @param parser          configured JavaParser instance
     * @param log             writer for progress and summary output
     * @return {@code 0} on success, {@code 1} when the mismatch limit is
     *         exceeded or a fatal error occurs
     * @throws IOException if the CSV file or source files cannot be read or
     *                     written
     */
    /* default */ static int apply(Path csvFile, List<Path> roots, List<String> fileSuffixes,
            Set<String> testAnnotations, int mismatchLimit, JavaParser parser, PrintWriter log)
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
        Map<Path, List<MethodKey>> sourceIndex = buildSourceIndex(roots, fileSuffixes, testAnnotations, parser);

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
        return applyFilesLoop(sourceIndex, desiredState, testAnnotations, parser, mismatchCount, log);
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
            Map<String, ScanRecord> desiredState, Set<String> testAnnotations,
            JavaParser parser, int mismatchCount, PrintWriter log) {
        int modifiedFiles = 0;
        int totalChanges = 0;
        boolean hadErrors = false;

        for (Map.Entry<Path, List<MethodKey>> entry : sourceIndex.entrySet()) {
            Path path = entry.getKey();
            if (!hasRelevantMethod(entry.getValue(), desiredState)) {
                continue;
            }
            try {
                int changes = applyToFile(path, desiredState, testAnnotations, parser);
                if (changes > 0) {
                    modifiedFiles++;
                    totalChanges += changes;
                    log.println("Modified: " + path + " (+" + changes + " change(s))");
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
     * Applies desired-state annotations to the matching methods in a single
     * source file and writes the file back when at least one change was made.
     *
     * @param path         source file to modify
     * @param desiredState desired annotation state keyed by {@code fqcn::method}
     * @param testAnnotations annotation simple names identifying test methods
     * @param parser       configured JavaParser instance
     * @return total number of annotation changes (adds + removals) made to the
     *         file
     * @throws IOException if the file cannot be read or written
     */
    private static int applyToFile(Path path, Map<String, ScanRecord> desiredState,
            Set<String> testAnnotations, JavaParser parser) throws IOException {

        ParseResult<CompilationUnit> parseResult = parser.parse(path);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("Failed to parse: " + path + " — " + parseResult.getProblems());
            }
            return 0;
        }

        CompilationUnit cu = parseResult.getResult().orElseThrow();
        LexicalPreservingPrinter.setup(cu);

        String packageName = cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString).orElse("");

        Set<String> effective = AnnotationInspector.effectiveAnnotations(cu, testAnnotations);

        boolean needsTagImport = false;
        boolean needsDisplayNameImport = false;
        int totalChanges = 0;

        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqcn = buildFqcn(packageName, clazz.getNameAsString());

            for (MethodDeclaration method : clazz.getMethods()) {
                if (!AnnotationInspector.isJUnitTest(method, effective)) {
                    continue;
                }
                String methodKey = key(fqcn, method.getNameAsString());
                ScanRecord desired = desiredState.get(methodKey);
                if (desired == null) {
                    // Method is in source but not in CSV — already counted as mismatch; skip.
                    continue;
                }

                List<String> desiredTags = desired.tags();
                // null means the column was absent from the CSV (old format) — leave @DisplayName untouched.
                // "" means the column was present but empty — remove @DisplayName.
                String desiredDisplayName = desired.displayName();

                TagApplier.MethodApplyResult result = TagApplier.applyDesiredState(
                        method, desiredTags, desiredDisplayName);

                if (result.modified()) {
                    int changes = result.tagsAdded() + result.tagsRemoved()
                            + (result.displayNameChanged() ? 1 : 0);
                    totalChanges += changes;
                    if (result.needsTagImport()) {
                        needsTagImport = true;
                    }
                    if (result.needsDisplayNameImport()) {
                        needsDisplayNameImport = true;
                    }
                }
            }
        }

        if (totalChanges > 0) {
            if (needsTagImport) {
                cu.addImport(TagApplier.IMPORT_TAG);
            }
            if (needsDisplayNameImport) {
                cu.addImport(TagApplier.IMPORT_DISPLAY_NAME);
            }
            Files.writeString(path, LexicalPreservingPrinter.print(cu), StandardCharsets.UTF_8);
        }

        return totalChanges;
    }

    /**
     * Scans source roots and builds an index mapping each source file to the
     * list of test methods it contains.
     *
     * @param roots           source root directories
     * @param fileSuffixes    filename suffixes selecting test source files
     * @param testAnnotations annotation simple names identifying test methods
     * @param parser          configured JavaParser instance
     * @return map from source file path to list of {@link MethodKey} entries;
     *         never {@code null}
     * @throws IOException if a file tree cannot be traversed
     */
    private static Map<Path, List<MethodKey>> buildSourceIndex(List<Path> roots,
            List<String> fileSuffixes, Set<String> testAnnotations, JavaParser parser)
            throws IOException {

        Map<Path, List<MethodKey>> index = new HashMap<>();

        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    String name = path.getFileName().toString();
                    if (fileSuffixes.stream().noneMatch(name::endsWith)) {
                        continue;
                    }
                    try {
                        List<MethodKey> methods = extractMethodKeys(path, testAnnotations, parser);
                        if (!methods.isEmpty()) {
                            index.put(path, methods);
                        }
                    } catch (IOException e) {
                        if (LOG.isLoggable(Level.WARNING)) {
                            LOG.log(Level.WARNING, "Cannot read: " + path, e);
                        }
                    }
                }
            }
        }

        return index;
    }

    /**
     * Parses a single source file and returns the (FQCN, method-name) pairs
     * for all JUnit test methods it contains.
     *
     * @param path            source file to parse
     * @param testAnnotations annotation simple names identifying test methods
     * @param parser          configured JavaParser instance
     * @return list of method keys; empty when no test methods are found or
     *         the file cannot be parsed
     * @throws IOException if the file cannot be read
     */
    private static List<MethodKey> extractMethodKeys(Path path, Set<String> testAnnotations,
            JavaParser parser) throws IOException {

        ParseResult<CompilationUnit> parseResult = parser.parse(path);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return List.of();
        }

        CompilationUnit cu = parseResult.getResult().orElseThrow();
        String packageName = cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString).orElse("");
        Set<String> effective = AnnotationInspector.effectiveAnnotations(cu, testAnnotations);

        List<MethodKey> keys = new ArrayList<>();
        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqcn = buildFqcn(packageName, clazz.getNameAsString());
            for (MethodDeclaration method : clazz.getMethods()) {
                if (AnnotationInspector.isJUnitTest(method, effective)) {
                    keys.add(new MethodKey(fqcn, method.getNameAsString()));
                }
            }
        }
        return keys;
    }

    private static String key(String fqcn, String method) {
        return fqcn + "::" + method;
    }

    private static String buildFqcn(String packageName, String className) {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    /** Lightweight tuple holding a fully qualified class name and a method name. */
    private record MethodKey(String fqcn, String method) {
    }
}
