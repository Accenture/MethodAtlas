package org.egothor.methodatlas.discovery.jvm;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/**
 * {@link SourcePatcher} implementation for Java source files.
 *
 * <p>
 * Applies annotation changes (tags and display names) to Java test source
 * files driven by a reviewed MethodAtlas CSV export. This class contains the
 * logic for writing {@code @Tag} and {@code @DisplayName} annotations back
 * into {@code .java} source files using JavaParser's lexical-preserving
 * printer so that unrelated formatting is left intact.
 * </p>
 *
 * <p>
 * The implementation handles a desired-state specification: for each test
 * method it receives the exact set of {@code @Tag} annotations and the
 * {@code @DisplayName} text that the method should have after patching.
 * Existing tags not in the desired set are removed; desired tags not already
 * present are added.
 * </p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>
 * This class is registered as a {@link SourcePatcher} provider via
 * {@code META-INF/services/org.egothor.methodatlas.api.SourcePatcher}.
 * The orchestration layer loads it automatically via
 * {@link java.util.ServiceLoader}.
 * </p>
 *
 * @see SourcePatcher
 * @see AnnotationInspector
 */
public final class JavaSourcePatcher implements SourcePatcher {

    private static final Logger LOG = Logger.getLogger(JavaSourcePatcher.class.getName());

    /** Fully qualified name of {@code @DisplayName} for import management. */
    /* default */ static final String IMPORT_DISPLAY_NAME = "org.junit.jupiter.api.DisplayName";

    /** Fully qualified name of {@code @Tag} for import management. */
    /* default */ static final String IMPORT_TAG = "org.junit.jupiter.api.Tag";

    private static final String ANNOTATION_DISPLAY_NAME = "DisplayName";
    private static final String ANNOTATION_TAG = "Tag";

    private List<String> fileSuffixes = List.of("Test.java");

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     */
    public JavaSourcePatcher() {
        // Required by ServiceLoader
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Stores the configured file suffixes used by {@link #supports(Path)}.
     * Suffixes that target other plugins (e.g. {@code "dotnet:Test.cs"}) are
     * automatically excluded via
     * {@link TestDiscoveryConfig#fileSuffixesFor(String)}.
     * When no global or {@code "java:"}-prefixed entries remain, the default
     * suffix {@code "Test.java"} is used.
     * </p>
     */
    @Override
    public String pluginId() {
        return "java";
    }

    @Override
    public void configure(TestDiscoveryConfig config) {
        List<String> suffixes = config.fileSuffixesFor(pluginId());
        this.fileSuffixes = suffixes.isEmpty() ? List.of("Test.java") : suffixes;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Returns {@code true} if the source file's name ends with any of the
     * configured file suffixes (default: {@code "Test.java"}).
     * </p>
     */
    @Override
    public boolean supports(Path sourceFile) {
        Path fileNamePath = sourceFile.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String name = fileNamePath.toString();
        return fileSuffixes.stream().anyMatch(name::endsWith);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Parses the source file with JavaParser (Java 21 language level) and
     * returns a map from FQCN to the list of simple test-method names declared
     * in each class. Methods are identified using
     * {@link AnnotationInspector#isJUnitTest} with the default test annotations.
     * </p>
     */
    @Override
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    public Map<String, List<String>> discoverMethodsByClass(Path sourceFile) throws IOException {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(cfg);

        ParseResult<CompilationUnit> parseResult = parser.parse(sourceFile);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("discoverMethodsByClass: failed to parse: " + sourceFile
                        + " — " + parseResult.getProblems());
            }
            return Map.of();
        }

        CompilationUnit cu = parseResult.getResult().orElseThrow();
        String packageName = cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString).orElse("");
        Set<String> effective = AnnotationInspector.effectiveAnnotations(
                cu, AnnotationInspector.DEFAULT_TEST_ANNOTATIONS);

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqcn = buildFqcn(packageName, clazz.getNameAsString());
            List<String> names = new ArrayList<>();
            for (MethodDeclaration method : clazz.getMethods()) {
                if (AnnotationInspector.isJUnitTest(method, effective)) {
                    names.add(method.getNameAsString());
                }
            }
            if (!names.isEmpty()) {
                result.put(fqcn, names);
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Parses the source file with JavaParser (Java 21 language level) and
     * applies the desired annotation state to each matching test method.
     * The file is written back using {@link LexicalPreservingPrinter} so that
     * formatting outside the modified annotations is preserved.
     * </p>
     *
     * <p>
     * The desired state for each method is driven by the {@code tagsToApply}
     * and {@code displayNames} maps:
     * </p>
     * <ul>
     *   <li>All existing {@code @Tag} and {@code @Tags} annotations are removed
     *       and replaced with exactly the tags listed in {@code tagsToApply}.</li>
     *   <li>{@code displayNames} value of {@code null} or absent key — leaves
     *       any existing {@code @DisplayName} untouched.</li>
     *   <li>{@code displayNames} value of {@code ""} — removes any existing
     *       {@code @DisplayName}.</li>
     *   <li>{@code displayNames} value of non-empty text — sets the
     *       {@code @DisplayName} to that text.</li>
     * </ul>
     *
     * @return number of annotation changes made; {@code 0} if the file was not
     *         modified
     */
    @Override
    public int patch(Path sourceFile,
                     Map<String, List<String>> tagsToApply,
                     Map<String, String> displayNames,
                     PrintWriter diagnostics) throws IOException {

        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(cfg);

        ParseResult<CompilationUnit> parseResult = parser.parse(sourceFile);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("Failed to parse: " + sourceFile + " — " + parseResult.getProblems());
            }
            return 0;
        }

        CompilationUnit cu = parseResult.getResult().orElseThrow();
        LexicalPreservingPrinter.setup(cu);

        String packageName = cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString).orElse("");

        Set<String> effective = AnnotationInspector.effectiveAnnotations(cu, AnnotationInspector.DEFAULT_TEST_ANNOTATIONS);

        boolean needsTagImport = false;
        boolean needsDisplayNameImport = false;
        int totalChanges = 0;

        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqcn = buildFqcn(packageName, clazz.getNameAsString());

            for (MethodDeclaration method : clazz.getMethods()) {
                if (!AnnotationInspector.isJUnitTest(method, effective)) {
                    continue;
                }
                String methodName = method.getNameAsString();
                List<String> desiredTags = tagsToApply.get(methodName);
                String desiredDisplayName = displayNames.get(methodName);

                if (desiredTags == null && !displayNames.containsKey(methodName)) {
                    // Method is not mentioned in either map — leave unchanged.
                    continue;
                }

                MethodApplyResult result = applyDesiredState(method, desiredTags, desiredDisplayName);

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
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine("Patched method " + fqcn + "#" + methodName + ": " + changes + " change(s)");
                    }
                }
            }
        }

        if (totalChanges > 0) {
            if (needsTagImport) {
                cu.addImport(IMPORT_TAG);
            }
            if (needsDisplayNameImport) {
                cu.addImport(IMPORT_DISPLAY_NAME);
            }
            Files.writeString(sourceFile, LexicalPreservingPrinter.print(cu), StandardCharsets.UTF_8);
            diagnostics.println("Patched: " + sourceFile + " (+" + totalChanges + " change(s))");
        }
        return totalChanges;
    }

    /**
     * Applies a desired annotation state to a single test method declaration.
     *
     * <p>All existing {@code @Tag} and {@code @Tags} annotations are removed and
     * replaced with exactly the tags from {@code desiredTags}. The
     * {@code @DisplayName} annotation is driven by {@code desiredDisplayName}
     * according to a three-way contract:</p>
     * <ul>
     *   <li>{@code null} — column was absent from the source CSV (old format):
     *       the existing {@code @DisplayName} annotation is left untouched</li>
     *   <li>{@code ""} — column was present but empty: any existing
     *       {@code @DisplayName} is removed</li>
     *   <li>non-empty text — the desired display name: any existing
     *       {@code @DisplayName} is replaced with the new value</li>
     * </ul>
     *
     * @param method             method declaration to modify
     * @param desiredTags        exact set of {@code @Tag} values to apply; {@code null}
     *                           is treated as an empty list (all tags removed)
     * @param desiredDisplayName desired {@code @DisplayName} text; {@code null} means
     *                           leave unchanged; {@code ""} means remove; non-empty
     *                           means set to this value
     * @return result describing what changed; never {@code null}
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    /* default */ static MethodApplyResult applyDesiredState(MethodDeclaration method,
            List<String> desiredTags, String desiredDisplayName) {
        // Handle @DisplayName
        // null  → column absent from CSV (old format): leave @DisplayName unchanged
        // ""    → column present but empty: remove @DisplayName
        // text  → set @DisplayName to the given text
        boolean displayNameChanged = false;
        if (desiredDisplayName != null && !desiredDisplayName.isEmpty()) {
            method.getAnnotations().removeIf(a -> ANNOTATION_DISPLAY_NAME.equals(a.getNameAsString()));
            method.addSingleMemberAnnotation(ANNOTATION_DISPLAY_NAME,
                    new StringLiteralExpr(desiredDisplayName));
            displayNameChanged = true;
        } else if (desiredDisplayName != null) {
            // desiredDisplayName is "" — remove any existing @DisplayName
            boolean hadDisplayName = method.getAnnotations().stream()
                    .anyMatch(a -> ANNOTATION_DISPLAY_NAME.equals(a.getNameAsString()));
            method.getAnnotations().removeIf(a -> ANNOTATION_DISPLAY_NAME.equals(a.getNameAsString()));
            if (hadDisplayName) {
                displayNameChanged = true;
            }
        }
        // else desiredDisplayName == null → no change to @DisplayName

        // Handle @Tag annotations — only mutate the AST if the sets differ.
        Set<String> existingTags = new HashSet<>(AnnotationInspector.getTagValues(method));
        Set<String> desiredTagSet = new HashSet<>();
        if (desiredTags != null) {
            for (String tag : desiredTags) {
                if (tag != null && !tag.isBlank()) {
                    desiredTagSet.add(tag);
                }
            }
        }

        int tagsAdded = 0;
        int tagsRemoved = 0;
        if (!existingTags.equals(desiredTagSet)) {
            method.getAnnotations().removeIf(a -> ANNOTATION_TAG.equals(a.getNameAsString())
                    || "Tags".equals(a.getNameAsString()));
            tagsRemoved = existingTags.size();
            for (String tag : desiredTagSet) {
                method.addSingleMemberAnnotation(ANNOTATION_TAG, new StringLiteralExpr(tag));
                tagsAdded++;
            }
        }

        return new MethodApplyResult(tagsAdded, tagsRemoved, displayNameChanged);
    }

    private static String buildFqcn(String packageName, String className) {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    /**
     * Result of applying a desired annotation state to a single method declaration.
     *
     * @param tagsAdded          number of {@code @Tag} annotations added
     * @param tagsRemoved        number of {@code @Tag} annotations removed
     * @param displayNameChanged whether the {@code @DisplayName} annotation was
     *                           set or removed
     */
    /* default */ record MethodApplyResult(int tagsAdded, int tagsRemoved, boolean displayNameChanged) {

        /**
         * Returns {@code true} when at least one annotation was added, removed,
         * or changed.
         *
         * @return {@code true} if the method was modified
         */
        /* default */ boolean modified() {
            return tagsAdded > 0 || tagsRemoved > 0 || displayNameChanged;
        }

        /**
         * Returns {@code true} when a {@code @Tag} import may be required.
         *
         * @return {@code true} if tags were added
         */
        /* default */ boolean needsTagImport() { return tagsAdded > 0; }

        /**
         * Returns {@code true} when a {@code @DisplayName} import may be required.
         *
         * @return {@code true} if the display name was changed
         */
        /* default */ boolean needsDisplayNameImport() { return displayNameChanged; }
    }
}
