package org.egothor.methodatlas.discovery.go;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.discovery.go.internal.GoTestVisitor;
import org.egothor.methodatlas.discovery.go.internal.MethodInfo;
import org.egothor.methodatlas.discovery.go.parser.GoTestLexer;
import org.egothor.methodatlas.discovery.go.parser.GoTestParser;

/**
 * {@link TestDiscovery} implementation for Go source trees.
 *
 * <p>Scans a directory root for {@code *_test.go} files (configurable via
 * {@link TestDiscoveryConfig#fileSuffixesFor(String)}), parses each with the
 * ANTLR4-generated {@code GoTest} grammar, and emits one
 * {@link DiscoveredMethod} per Go test function found.</p>
 *
 * <h2>Test-function detection</h2>
 * <p>A function is identified as a test if its signature matches the
 * {@code go test} specification:</p>
 * <pre>
 *   func TestXxx(t *testing.T)
 * </pre>
 * <p>where {@code Xxx} starts with an upper-case letter or underscore.
 * Benchmark ({@code BenchmarkXxx}), Example ({@code ExampleXxx}), and Fuzz
 * ({@code FuzzXxx}) functions are not recognised by the visitor and are
 * therefore excluded.</p>
 *
 * <h2>Tags and display names</h2>
 * <p>Go has no annotation-based tag or display-name system; both fields are
 * always empty / {@code null} in the emitted {@link DiscoveredMethod}.</p>
 *
 * <h2>Parser scope</h2>
 * <p>The {@code GoTest} grammar is structural: it covers package declarations,
 * import declarations, and function/method declarations, treating function
 * bodies as opaque balanced-brace content.  It is not a full implementation
 * of the Go specification.  When a parse error occurs, a {@code WARNING} is
 * logged; ANTLR4 error recovery then continues so remaining test functions
 * are still discovered.</p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>Registered via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.</p>
 *
 * @see TestDiscovery
 * @see DiscoveredMethod
 * @see GoTestVisitor
 */
public final class GoTestDiscovery implements TestDiscovery {

    private static final Logger LOG = Logger.getLogger(GoTestDiscovery.class.getName());

    private static final String DEFAULT_SUFFIX = "_test.go";

    private List<String> fileSuffixes = List.of(DEFAULT_SUFFIX);
    private final AtomicBoolean errors = new AtomicBoolean();

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     */
    public GoTestDiscovery() {
        // Required by ServiceLoader
    }

    @Override
    public String pluginId() {
        return "go";
    }

    /**
     * Configures file suffixes from the supplied {@link TestDiscoveryConfig}.
     *
     * <p>Calls {@link TestDiscoveryConfig#fileSuffixesFor(String)} with
     * {@code "go"}; if the result is empty the default {@code "_test.go"}
     * suffix is used.</p>
     *
     * @param config runtime configuration supplied by the calling application;
     *               never {@code null}
     */
    @Override
    public void configure(TestDiscoveryConfig config) {
        List<String> suffixes = config.fileSuffixesFor(pluginId());
        this.fileSuffixes = suffixes.isEmpty() ? List.of(DEFAULT_SUFFIX) : suffixes;
    }

    /**
     * Scans {@code root} for Go test files and returns discovered test functions.
     *
     * <p>The returned stream is fully materialised before being returned; it is
     * safe to call this method multiple times (e.g. once per scan root).</p>
     *
     * @param root directory to scan; must be an existing directory
     * @return stream of discovered test methods; never {@code null}
     * @throws IOException if traversing the file tree fails
     */
    @Override
    public Stream<DiscoveredMethod> discover(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        List<DiscoveredMethod> results = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(this::isGoTestFile)
                .forEach(file -> {
                    try {
                        discoverInFile(file, root, results);
                    } catch (Exception e) {
                        errors.set(true);
                        if (LOG.isLoggable(Level.WARNING)) {
                            LOG.log(Level.WARNING, "Failed to process: " + file, e);
                        }
                    }
                });
        }
        return results.stream();
    }

    /**
     * Returns {@code true} if any file could not be processed during discovery.
     *
     * @return {@code true} when at least one per-file error was encountered
     */
    @Override
    public boolean hadErrors() {
        return errors.get();
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private boolean isGoTestFile(Path path) {
        Path fn = path.getFileName();
        if (fn == null) {
            return false;
        }
        String name = fn.toString();
        return fileSuffixes.stream().anyMatch(name::endsWith);
    }

    private void discoverInFile(Path file, Path root,
                                List<DiscoveredMethod> results) throws IOException {
        GoTestParser.SourceFileContext tree = parse(file);
        if (tree == null) {
            return;
        }

        GoTestVisitor visitor = new GoTestVisitor();
        visitor.visit(tree);

        List<MethodInfo> methods = visitor.getDiscoveredMethods();
        if (methods.isEmpty()) {
            return;
        }

        String packageName = visitor.getPackageName();
        String fqcn = buildFqcn(file, root, packageName);
        String stem = buildFileStem(file, root);
        SourceContent content = buildSourceContent(file);

        for (MethodInfo m : methods) {
            int loc = m.endLine() - m.beginLine() + 1;
            results.add(new DiscoveredMethod(
                    fqcn,
                    m.name(),
                    m.beginLine(),
                    m.endLine(),
                    loc,
                    List.of(),
                    null,
                    file,
                    stem,
                    content));
        }
    }

    private GoTestParser.SourceFileContext parse(Path file) throws IOException {
        GoTestLexer lexer = new GoTestLexer(CharStreams.fromPath(file));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        GoTestParser parser = new GoTestParser(tokens);
        parser.removeErrorListeners();
        List<String> syntaxErrors = new ArrayList<>();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine,
                                    String msg, RecognitionException e) {
                syntaxErrors.add(file + ":" + line + ":" + charPositionInLine + ": " + msg);
            }
        });
        GoTestParser.SourceFileContext tree = parser.sourceFile();
        if (!syntaxErrors.isEmpty()) {
            errors.set(true);
            if (LOG.isLoggable(Level.WARNING)) {
                syntaxErrors.forEach(err -> LOG.warning("Go parse error: " + err));
            }
        }
        return tree;
    }

    // ── Package-private static helpers (testable) ──────────────────────────

    /**
     * Derives the fully-qualified class name from the file's parent directory
     * relative to the scan root.
     *
     * <p>The path segments of the parent directory relative to {@code root}
     * are joined with {@code "."}.  If the file resides directly under
     * {@code root} (no parent segments) or relativization fails,
     * {@code packageName} is returned as the fallback.</p>
     *
     * @param file        path to the Go source file
     * @param root        scan root directory
     * @param packageName package name extracted from the file (used as fallback)
     * @return dot-separated identifier representing the file's directory path
     */
    /* default */ static String buildFqcn(Path file, Path root, String packageName) {
        try {
            Path relParent = root.relativize(file.getParent());
            if (relParent.getNameCount() == 0 || relParent.toString().isEmpty()) {
                return packageName;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < relParent.getNameCount(); i++) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(relParent.getName(i).toString());
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return packageName;
        }
    }

    /**
     * Derives the dot-separated file stem by relativizing {@code file} from
     * {@code root}, joining segments with {@code "."}, and stripping the
     * {@code _test.go} suffix from the last segment.
     *
     * @param file path to the Go test source file
     * @param root scan root directory
     * @return dot-separated file stem without the {@code _test.go} extension
     */
    /* default */ static String buildFileStem(Path file, Path root) {
        Path rel = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            String part = rel.getName(i).toString();
            if (i == rel.getNameCount() - 1 && part.endsWith(DEFAULT_SUFFIX)) {
                part = part.substring(0, part.length() - DEFAULT_SUFFIX.length());
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static SourceContent buildSourceContent(Path file) {
        return SourceContent.ofFile(file);
    }
}
