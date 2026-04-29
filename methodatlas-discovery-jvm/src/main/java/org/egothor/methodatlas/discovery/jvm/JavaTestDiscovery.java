package org.egothor.methodatlas.discovery.jvm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithName;

/**
 * {@link TestDiscovery} implementation for Java source trees.
 *
 * <p>
 * Traverses a directory root, selects files whose names end with any of the
 * configured suffixes, parses each file with JavaParser, and emits one
 * {@link DiscoveredMethod} per JUnit test method found.
 * </p>
 *
 * <p>
 * Test-framework detection is automatic: import declarations are inspected to
 * select the appropriate annotation set for JUnit 4, JUnit 5 (Jupiter), or
 * TestNG. Callers can override this by supplying a custom marker set at
 * construction time or via {@link #configure}.
 * </p>
 *
 * <p>
 * The {@link DiscoveredMethod#sourceContent()} provider returned for each
 * method captures {@code clazz.toString()} (the JavaParser pretty-print of
 * the class declaration) in memory during scanning. All methods belonging to
 * the same class share a single {@link SourceContent} instance backed by the
 * same captured string.
 * </p>
 *
 * <p>
 * Instances are reusable: {@link #discover(Path)} can be called multiple times
 * (e.g. once per scan root). Error tracking is cumulative across calls.
 * </p>
 *
 * <h2>ServiceLoader usage</h2>
 *
 * <p>
 * This class is registered as a {@link TestDiscovery} provider via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.
 * When loaded that way the no-arg constructor is used and
 * {@link #configure(TestDiscoveryConfig)} must be called before
 * {@link #discover(Path)}.
 * </p>
 *
 * @see AnnotationInspector
 * @see TestDiscovery
 * @see TestDiscoveryConfig
 */
public final class JavaTestDiscovery implements TestDiscovery {

    private static final Logger LOG = Logger.getLogger(JavaTestDiscovery.class.getName());

    private JavaParser parser;
    private List<String> fileSuffixes;
    private Set<String> testAnnotations;
    private boolean errors;

    /**
     * No-arg constructor for use by {@link java.util.ServiceLoader}.
     *
     * <p>
     * {@link #configure(TestDiscoveryConfig)} must be called before the first
     * call to {@link #discover(Path)}.
     * </p>
     */
    public JavaTestDiscovery() {
        // Required by ServiceLoader; call configure(TestDiscoveryConfig) before first use
    }

    /**
     * Creates a fully configured scanner for programmatic use.
     *
     * <p>
     * Use this constructor when you already have a {@link JavaParser} instance
     * configured for a specific language level, or in tests that supply a
     * custom parser. For {@link java.util.ServiceLoader}-based loading, prefer
     * the no-arg constructor followed by {@link #configure}.
     * </p>
     *
     * @param parser          configured JavaParser instance
     * @param fileSuffixes    one or more filename suffixes used to select source
     *                        files; a file is included if its name ends with any
     *                        of the listed suffixes (e.g. {@code ["Test.java"]})
     * @param testAnnotations set of annotation simple names that identify test
     *                        methods; pass
     *                        {@link AnnotationInspector#DEFAULT_TEST_ANNOTATIONS}
     *                        to enable automatic framework detection; maps to
     *                        {@link org.egothor.methodatlas.api.TestDiscoveryConfig#testMarkers()}
     *                        when configured via ServiceLoader
     */
    public JavaTestDiscovery(JavaParser parser, List<String> fileSuffixes,
            Set<String> testAnnotations) {
        this.parser = parser;
        this.fileSuffixes = List.copyOf(fileSuffixes);
        this.testAnnotations = testAnnotations;
    }

    /**
     * Returns the unique identifier of this discovery provider: {@code "java"}.
     *
     * @return {@code "java"}
     */
    @Override
    public String pluginId() {
        return "java";
    }

    /**
     * Configures this provider from a {@link TestDiscoveryConfig}.
     *
     * <p>
     * Creates a {@link JavaParser} set to Java&nbsp;21 language level,
     * applies the supplied file suffixes, and uses
     * {@link org.egothor.methodatlas.api.TestDiscoveryConfig#testMarkers()}
     * as the annotation name set. When {@code testMarkers} is empty, automatic
     * framework detection is enabled via
     * {@link AnnotationInspector#DEFAULT_TEST_ANNOTATIONS}.
     * The {@link org.egothor.methodatlas.api.TestDiscoveryConfig#properties()}
     * map is not used by this provider; it is reserved for future extensions.
     * </p>
     *
     * <p>
     * This method may also be used to (re-)configure an existing instance
     * after it was created with the no-arg constructor.
     * </p>
     *
     * @param config runtime configuration; never {@code null}
     */
    @Override
    public void configure(TestDiscoveryConfig config) {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(LanguageLevel.JAVA_21);
        this.parser = new JavaParser(cfg);
        List<String> suffixes = config.fileSuffixesFor(pluginId());
        this.fileSuffixes = suffixes.isEmpty() ? List.of("Test.java") : suffixes;
        this.testAnnotations = config.testMarkers().isEmpty()
                ? AnnotationInspector.DEFAULT_TEST_ANNOTATIONS
                : config.testMarkers();
    }

    /**
     * Scans {@code root} and returns a stream of all discovered JUnit test
     * methods.
     *
     * <p>
     * The stream is fully materialized before being returned. Files that fail
     * to parse are logged as warnings and skipped; {@link #hadErrors()} will
     * return {@code true} after such a run.
     * </p>
     *
     * @param root directory to scan
     * @return stream of discovered test methods; never {@code null}
     * @throws IllegalStateException if {@link #configure} has not been called
     *                               on an instance created with the no-arg
     *                               constructor
     * @throws IOException           if traversing the file tree fails
     */
    @Override
    public Stream<DiscoveredMethod> discover(Path root) throws IOException {
        if (parser == null) {
            throw new IllegalStateException(
                    "JavaTestDiscovery is not configured. "
                    + "Call configure(TestDiscoveryConfig) before discover(Path).");
        }

        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO, "Scanning {0} for files matching {1}",
                    new Object[] { root, fileSuffixes });
        }

        List<DiscoveredMethod> result = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(path -> fileSuffixes.stream().anyMatch(s -> path.toString().endsWith(s)))
                    .toList();
            for (Path path : files) {
                processFile(root, path, result);
            }
        }

        return result.stream();
    }

    /** {@inheritDoc} */
    @Override
    public boolean hadErrors() {
        return errors;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void processFile(Path root, Path path, List<DiscoveredMethod> result) {
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(path);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING, "Failed to parse {0}: {1}",
                            new Object[] { path, parseResult.getProblems() });
                }
                errors = true;
                return;
            }

            CompilationUnit cu = parseResult.getResult().orElseThrow();
            Set<String> effective = AnnotationInspector.effectiveAnnotations(cu, testAnnotations);
            String packageName = cu.getPackageDeclaration()
                    .map(NodeWithName::getNameAsString).orElse("");

            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = buildFqcn(packageName, clazz.getNameAsString());
                String fileStem = buildFileStem(root, path, fqcn);

                List<MethodDeclaration> testMethods = clazz.findAll(MethodDeclaration.class).stream()
                        .filter(m -> AnnotationInspector.isJUnitTest(m, effective))
                        .toList();

                if (testMethods.isEmpty()) {
                    continue;
                }

                String classSource = clazz.toString();
                SourceContent sourceContent = () -> Optional.of(classSource);

                for (MethodDeclaration method : testMethods) {
                    int beginLine = method.getRange().map(r -> r.begin.line).orElse(0);
                    int endLine = method.getRange().map(r -> r.end.line).orElse(0);
                    int loc = AnnotationInspector.countLOC(method);
                    List<String> tags = AnnotationInspector.getTagValues(method);
                    String displayName = AnnotationInspector.getDisplayName(method);

                    result.add(new DiscoveredMethod(
                            fqcn,
                            method.getNameAsString(),
                            beginLine,
                            endLine,
                            loc,
                            tags,
                            displayName,
                            path,
                            fileStem,
                            sourceContent));
                }
            }

        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Cannot read file: " + path, e);
            }
            errors = true;
        }
    }

    private static String buildFqcn(String packageName, String className) {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    /**
     * Computes the dot-separated file stem used to name work and response files
     * in the manual AI workflow.
     *
     * @param root  scan root directory
     * @param file  source file being processed
     * @param fqcn  fully qualified class name of the class in {@code file}
     * @return dot-separated file stem; never {@code null}
     */
    /* default */ static String buildFileStem(Path root, Path file, String fqcn) {
        Path rel = root.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize());
        String pathStr = rel.toString().replace('\\', '/').replace('/', '.');
        if (pathStr.endsWith(".java")) {
            pathStr = pathStr.substring(0, pathStr.length() - 5);
        }
        String pathLastPart = pathStr.contains(".")
                ? pathStr.substring(pathStr.lastIndexOf('.') + 1) : pathStr;
        String fqcnLastPart = fqcn.contains(".")
                ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
        if (!pathLastPart.equals(fqcnLastPart)) {
            return pathStr + "." + fqcnLastPart;
        }
        return pathStr;
    }
}
