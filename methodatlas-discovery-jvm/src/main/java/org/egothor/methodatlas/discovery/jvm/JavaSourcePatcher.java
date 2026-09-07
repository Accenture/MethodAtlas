package org.egothor.methodatlas.discovery.jvm;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/**
 * {@link SourcePatcher} implementation for Java source files.
 *
 * <p>
 * Applies annotation changes (tags and display names) to Java test source
 * files driven by a reviewed MethodAtlas CSV export. This class contains the
 * logic for writing tag and display-name annotations back into {@code .java}
 * source files using JavaParser's lexical-preserving printer so that
 * unrelated formatting is left intact.
 * </p>
 *
 * <h2>JUnit version detection and configuration</h2>
 * <p>
 * The patcher detects the test framework for each file from its import
 * declarations and applies the appropriate annotation style:
 * </p>
 * <ul>
 *   <li><strong>JUnit 5 (Jupiter)</strong> — writes {@code @Tag("value")}
 *       and {@code @DisplayName("text")}.</li>
 *   <li><strong>JUnit 4</strong> — writes {@code @Category(SomeClass.class)};
 *       {@code @DisplayName} has no JUnit 4 equivalent and is skipped with a
 *       diagnostic. The tag-to-class mapping must be supplied by the operator
 *       via the {@code categoryClasses} property (see below).</li>
 * </ul>
 * <p>
 * When no recognisable framework imports are found in a file, the patcher
 * falls back to a configured default (default: JUnit 5). The default can be
 * overridden via the {@code tagFramework} property in
 * {@link TestDiscoveryConfig#properties()}.
 * </p>
 *
 * <h2>Plugin properties consumed by this patcher</h2>
 * <dl>
 *   <dt>{@code tagFramework}</dt>
 *   <dd>Single value: {@code junit5} (default) or {@code junit4}. Determines
 *       which annotation style is used when no framework imports are found in
 *       a source file. Command-line: {@code -property tagFramework=junit4}.
 *       YAML:
 *       <pre>
 * properties:
 *   tagFramework:
 *     - junit4
 *       </pre>
 *   </dd>
 *   <dt>{@code categoryClasses}</dt>
 *   <dd>List of {@code tagName=fully.qualified.ClassName} entries that map
 *       each MethodAtlas tag string to the fully qualified name of the JUnit 4
 *       {@code @Category} class to write. Required for JUnit 4 files; ignored
 *       for JUnit 5 files. Tags without a mapping in JUnit 4 mode are skipped
 *       with a warning. Command-line:
 *       {@code -property categoryClasses=security=com.example.SecurityTest}.
 *       YAML:
 *       <pre>
 * properties:
 *   categoryClasses:
 *     - security=com.example.SecurityTest
 *     - performance=com.example.PerformanceTest
 *       </pre>
 *   </dd>
 * </dl>
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
 * @see JavaTestFramework
 */
public final class JavaSourcePatcher implements SourcePatcher {

    private static final Logger LOG = Logger.getLogger(JavaSourcePatcher.class.getName());

    /** Fully qualified name of {@code @DisplayName} for import management. */
    /* default */ static final String IMPORT_DISPLAY_NAME = "org.junit.jupiter.api.DisplayName";

    /** Fully qualified name of {@code @Tag} for import management. */
    /* default */ static final String IMPORT_TAG = "org.junit.jupiter.api.Tag";

    /** Fully qualified name of {@code @Category} for import management. */
    /* default */ static final String IMPORT_CATEGORY = "org.junit.experimental.categories.Category";

    private static final String ANNOTATION_DISPLAY_NAME = "DisplayName";
    private static final String ANNOTATION_TAG = "Tag";
    private static final String ANNOTATION_CATEGORY = "Category";

    /** Pattern that a fully qualified class name must match. */
    private static final Pattern FQCN_PATTERN =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private List<String> fileSuffixes = List.of("Test.java");

    /** Default framework used when no framework imports are detected in a file. */
    private JavaTestFramework defaultFramework = JavaTestFramework.JUNIT5;

    /** Maps MethodAtlas tag string → fully qualified category class name (JUnit 4 mode). */
    private Map<String, String> categoryClassMap = Map.of();

    /**
     * Reusable JavaParser (Java 21 language level). JavaParser resets its state
     * on each {@code parse} call, so a single instance serves every sequential
     * parse; the patcher is used single-threaded, mirroring
     * {@code JavaTestDiscovery}.
     */
    private final JavaParser parser;

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     */
    public JavaSourcePatcher() {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(LanguageLevel.JAVA_21);
        this.parser = new JavaParser(cfg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String pluginId() {
        return "java";
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
     *
     * <p>
     * Also reads JUnit-version and category-class-mapping properties from
     * {@link TestDiscoveryConfig#properties()}:
     * </p>
     * <ul>
     *   <li>{@code tagFramework} — single value {@code junit5} or {@code junit4};
     *       sets the fallback framework for files without recognisable imports</li>
     *   <li>{@code categoryClasses} — list of {@code tagName=fqcn} entries
     *       mapping each tag string to a JUnit 4 {@code @Category} class</li>
     * </ul>
     */
    @Override
    public void configure(TestDiscoveryConfig config) {
        List<String> suffixes = config.fileSuffixesFor(pluginId());
        this.fileSuffixes = suffixes.isEmpty() ? List.of("Test.java") : suffixes;

        List<String> frameworkProp = config.properties().getOrDefault("tagFramework", List.of());
        if (!frameworkProp.isEmpty()) {
            String value = frameworkProp.get(0).toLowerCase(Locale.ROOT);
            switch (value) {
                case "junit4" -> this.defaultFramework = JavaTestFramework.JUNIT4;
                case "junit5" -> this.defaultFramework = JavaTestFramework.JUNIT5;
                default -> LOG.warning("Unknown tagFramework value '" + frameworkProp.get(0)
                        + "'; expected 'junit4' or 'junit5' — defaulting to junit5");
            }
        }

        List<String> classMappings = config.properties().getOrDefault("categoryClasses", List.of());
        Map<String, String> map = new LinkedHashMap<>();
        for (String entry : classMappings) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                LOG.warning("Invalid categoryClasses entry '" + entry
                        + "'; expected 'tagName=fqcn' format — skipped");
                continue;
            }
            String tagName = entry.substring(0, eq).strip();
            String fqcn = entry.substring(eq + 1).strip();
            if (!FQCN_PATTERN.matcher(fqcn).matches()) {
                LOG.warning("Invalid FQCN '" + fqcn
                        + "' in categoryClasses entry '" + entry + "' — skipped");
                continue;
            }
            map.put(tagName, fqcn);
        }
        this.categoryClassMap = Collections.unmodifiableMap(map);
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
     * The test framework is detected from the file's import declarations via
     * {@link AnnotationInspector#detectFramework}; when no framework imports are
     * present the configured {@code tagFramework} default is used.
     * </p>
     *
     * <p>
     * <strong>JUnit 5 mode</strong> — rewrites {@code @Tag} / {@code @Tags}
     * annotations and optionally {@code @DisplayName}.<br>
     * <strong>JUnit 4 mode</strong> — rewrites {@code @Category} /
     * {@code @Categories} annotations; {@code @DisplayName} has no JUnit 4
     * equivalent and is skipped with a diagnostic line per affected method.
     * </p>
     *
     * @return number of annotation changes made; {@code 0} if the file was not
     *         modified
     */
    @Override
    public int patch(Path sourceFile,
                     Map<String, List<String>> tagsToApply,
                     Map<String, String> displayNames,
                     PrintWriter diagnostics) throws IOException {

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

        JavaTestFramework framework = AnnotationInspector.detectFramework(cu).orElse(defaultFramework);
        Set<String> effective = AnnotationInspector.effectiveAnnotations(
                cu, AnnotationInspector.DEFAULT_TEST_ANNOTATIONS);

        boolean needsTagImport = false;
        boolean needsDisplayNameImport = false;
        boolean needsCategoryAnnotationImport = false;
        Set<String> categoryClassImports = new LinkedHashSet<>();
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
                    continue;
                }

                MethodApplyResult result;
                if (framework == JavaTestFramework.JUNIT4) {
                    if (desiredDisplayName != null && !desiredDisplayName.isEmpty()) {
                        diagnostics.println("[WARN] @DisplayName not supported for JUnit 4 method "
                                + fqcn + "#" + methodName + " — skipped");
                    }
                    result = applyDesiredStateJunit4(method, desiredTags, categoryClassMap, diagnostics);
                } else {
                    result = applyDesiredState(method, desiredTags, desiredDisplayName);
                }

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
                    if (result.needsCategoryImport()) {
                        needsCategoryAnnotationImport = true;
                        categoryClassImports.addAll(result.addedCategoryFqcns());
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
            if (needsCategoryAnnotationImport) {
                cu.addImport(IMPORT_CATEGORY);
                for (String classFqcn : categoryClassImports) {
                    cu.addImport(classFqcn);
                }
            }
            Files.writeString(sourceFile, LexicalPreservingPrinter.print(cu), StandardCharsets.UTF_8);
            diagnostics.println("Patched: " + sourceFile + " (+" + totalChanges + " change(s))");
        }
        return totalChanges;
    }

    /**
     * Applies a desired JUnit 5 annotation state to a single test method declaration.
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

        return new MethodApplyResult(tagsAdded, tagsRemoved, displayNameChanged, Set.of());
    }

    /**
     * Applies a desired JUnit 4 annotation state to a single test method declaration.
     *
     * <p>All existing {@code @Category} and {@code @Categories} annotations are
     * removed and replaced with exactly the categories resolved from
     * {@code desiredTags} via {@code categoryClassMap}. Tags that have no mapping
     * in {@code categoryClassMap} are skipped with a warning written to both the
     * JUL logger and {@code diagnostics}.</p>
     *
     * <p>{@code @DisplayName} is not touched by this method; the caller is
     * responsible for issuing the appropriate diagnostic when display names are
     * requested for a JUnit 4 method.</p>
     *
     * @param method           method declaration to modify
     * @param desiredTags      tags to apply; {@code null} treated as empty (all
     *                         categories removed)
     * @param categoryClassMap mapping from tag string to fully qualified category
     *                         class name
     * @param diagnostics      writer for human-readable diagnostic output
     * @return result describing what changed; never {@code null}
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    /* default */ static MethodApplyResult applyDesiredStateJunit4(
            MethodDeclaration method,
            List<String> desiredTags,
            Map<String, String> categoryClassMap,
            PrintWriter diagnostics) {

        Set<String> existingSimpleNames = getCategorySimpleNames(method);

        Set<String> desiredFqcns = new LinkedHashSet<>();
        if (desiredTags != null) {
            for (String tag : desiredTags) {
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                String fqcn = categoryClassMap.get(tag);
                if (fqcn == null) {
                    if (LOG.isLoggable(Level.WARNING)) {
                        LOG.warning("No categoryClasses mapping for tag '" + tag + "' — skipped");
                    }
                    diagnostics.println("[WARN] No categoryClasses mapping for tag '" + tag + "' — skipped");
                    continue;
                }
                desiredFqcns.add(fqcn);
            }
        }

        Set<String> desiredSimpleNames = new LinkedHashSet<>();
        for (String fqcn : desiredFqcns) {
            desiredSimpleNames.add(simpleNameOf(fqcn));
        }

        if (existingSimpleNames.equals(desiredSimpleNames)) {
            return new MethodApplyResult(0, 0, false, Set.of());
        }

        method.getAnnotations().removeIf(a -> ANNOTATION_CATEGORY.equals(a.getNameAsString())
                || "Categories".equals(a.getNameAsString()));
        int tagsRemoved = existingSimpleNames.size();

        if (desiredFqcns.isEmpty()) {
            return new MethodApplyResult(0, tagsRemoved, false, Set.of());
        }

        if (desiredFqcns.size() == 1) {
            String simpleName = desiredSimpleNames.iterator().next();
            method.addSingleMemberAnnotation(ANNOTATION_CATEGORY,
                    StaticJavaParser.parseExpression(simpleName + ".class"));
        } else {
            ArrayInitializerExpr array = new ArrayInitializerExpr();
            for (String simpleName : desiredSimpleNames) {
                array.getValues().add(StaticJavaParser.parseExpression(simpleName + ".class"));
            }
            method.addSingleMemberAnnotation(ANNOTATION_CATEGORY, array);
        }

        return new MethodApplyResult(desiredFqcns.size(), tagsRemoved, false, Set.copyOf(desiredFqcns));
    }

    /**
     * Extracts the simple names of classes declared in {@code @Category} and
     * {@code @Categories} annotations on the given method.
     *
     * @param method method declaration to inspect
     * @return set of simple class names; may be empty but never {@code null}
     */
    /* default */ static Set<String> getCategorySimpleNames(MethodDeclaration method) {
        Set<String> names = new LinkedHashSet<>();
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            if (ANNOTATION_CATEGORY.equals(name) || "Categories".equals(name)) {
                extractCategorySimpleNames(annotation, names);
            }
        }
        return names;
    }

    private static void extractCategorySimpleNames(AnnotationExpr annotation, Set<String> names) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            extractCategoryFromExpr(annotation.asSingleMemberAnnotationExpr().getMemberValue(), names);
        } else if (annotation.isNormalAnnotationExpr()) {
            annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()))
                    .forEach(p -> extractCategoryFromExpr(p.getValue(), names));
        }
    }

    private static void extractCategoryFromExpr(Expression expr, Set<String> names) {
        if (expr.isClassExpr()) {
            String typeName = expr.asClassExpr().getType().asString();
            int dot = typeName.lastIndexOf('.');
            names.add(dot >= 0 ? typeName.substring(dot + 1) : typeName);
        } else if (expr.isArrayInitializerExpr()) {
            for (Expression element : expr.asArrayInitializerExpr().getValues()) {
                extractCategoryFromExpr(element, names);
            }
        }
    }

    private static String simpleNameOf(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private static String buildFqcn(String packageName, String className) {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    /**
     * Result of applying a desired annotation state to a single method declaration.
     *
     * @param tagsAdded          number of tag/category annotations added
     * @param tagsRemoved        number of tag/category annotations removed
     * @param displayNameChanged whether the {@code @DisplayName} annotation was
     *                           set or removed (always {@code false} in JUnit 4 mode)
     * @param addedCategoryFqcns fully qualified names of {@code @Category} classes
     *                           that were written; empty for JUnit 5 patching
     */
    /* default */ record MethodApplyResult(
            int tagsAdded,
            int tagsRemoved,
            boolean displayNameChanged,
            Set<String> addedCategoryFqcns) {

        /**
         * Compact constructor — defensive copy of the category FQCNs set.
         */
        /* default */ MethodApplyResult {
            addedCategoryFqcns = Set.copyOf(addedCategoryFqcns);
        }

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
         * Returns {@code true} when a JUnit 5 {@code @Tag} import may be required.
         *
         * @return {@code true} if tags were added and no category annotation was written
         */
        /* default */ boolean needsTagImport() {
            return tagsAdded > 0 && addedCategoryFqcns.isEmpty();
        }

        /**
         * Returns {@code true} when a JUnit 4 {@code @Category} import may be required.
         *
         * @return {@code true} if category annotations were added
         */
        /* default */ boolean needsCategoryImport() {
            return !addedCategoryFqcns.isEmpty();
        }

        /**
         * Returns {@code true} when a {@code @DisplayName} import may be required.
         *
         * @return {@code true} if the display name was changed
         */
        /* default */ boolean needsDisplayNameImport() {
            return displayNameChanged;
        }
    }
}
