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

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
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
 * TestNG. Callers can override this by supplying a custom annotation set at
 * construction time.
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
 * @see AnnotationInspector
 * @see TestDiscovery
 */
public final class JavaTestDiscovery implements TestDiscovery {

    private static final Logger LOG = Logger.getLogger(JavaTestDiscovery.class.getName());

    private final JavaParser parser;
    private final List<String> fileSuffixes;
    private final Set<String> testAnnotations;
    private boolean errors;

    /**
     * Creates a new scanner with the supplied parser, file suffixes, and
     * annotation set.
     *
     * @param parser          configured JavaParser instance
     * @param fileSuffixes    one or more filename suffixes used to select source
     *                        files; a file is included if its name ends with any
     *                        of the listed suffixes (e.g. {@code ["Test.java"]})
     * @param testAnnotations set of annotation simple names that identify test
     *                        methods; pass
     *                        {@link AnnotationInspector#DEFAULT_TEST_ANNOTATIONS}
     *                        to enable automatic framework detection
     */
    public JavaTestDiscovery(JavaParser parser, List<String> fileSuffixes,
            Set<String> testAnnotations) {
        this.parser = parser;
        this.fileSuffixes = List.copyOf(fileSuffixes);
        this.testAnnotations = testAnnotations;
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
     * @throws IOException if traversing the file tree fails
     */
    @Override
    public Stream<DiscoveredMethod> discover(Path root) throws IOException {
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
    static String buildFileStem(Path root, Path file, String fqcn) {
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
