package org.egothor.methodatlas.discovery.go;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * {@link TestDiscovery} implementation for Go source trees.
 *
 * <p>Scans a directory root for {@code *_test.go} files (configurable via
 * {@link TestDiscoveryConfig#fileSuffixesFor(String)}), reads each file
 * line by line, and emits one {@link DiscoveredMethod} per Go test function
 * matching the standard Go testing convention.</p>
 *
 * <h2>Test function detection</h2>
 * <p>A function is identified as a test if its signature matches:</p>
 * <pre>
 *   func TestXxx(t *testing.T)
 * </pre>
 * <p>where {@code Xxx} starts with an upper-case letter or underscore per the
 * {@code go test} specification. Benchmark functions ({@code BenchmarkXxx}),
 * example functions ({@code ExampleXxx}), and fuzz targets ({@code FuzzXxx})
 * are intentionally excluded.</p>
 *
 * <h2>Tags and display names</h2>
 * <p>Go has no annotation-based tag or display-name system; both fields are
 * always empty / {@code null} in the emitted {@link DiscoveredMethod}.</p>
 *
 * <h2>Error handling</h2>
 * <p>Per-file {@link IOException}s are caught, logged at {@code WARNING}
 * level, and recorded via {@link #hadErrors()}.  A single unreadable file
 * does not abort the entire scan.</p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>Registered via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.</p>
 *
 * @see TestDiscovery
 * @see DiscoveredMethod
 */
public final class GoTestDiscovery implements TestDiscovery {

    private static final Logger LOG = Logger.getLogger(GoTestDiscovery.class.getName());

    /**
     * Regex that matches a Go test function declaration line.
     *
     * <p>Captured group 1 is the function name (e.g. {@code TestLoginValid}).</p>
     */
    private static final Pattern TEST_FUNC_PATTERN = Pattern.compile(
            "^func\\s+(Test(?:[A-Z_]\\w*)?)\\s*\\(\\s*\\w+\\s+\\*testing\\.T\\b");

    /**
     * Regex that matches a Go package declaration line.
     *
     * <p>Captured group 1 is the package name.</p>
     */
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^package\\s+(\\w+)");

    /** Default file suffix for Go test files. */
    private static final String DEFAULT_SUFFIX = "_test.go";

    private List<String> fileSuffixes = List.of(DEFAULT_SUFFIX);
    private boolean errors;

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
     * Scans {@code root} for Go test files and returns discovered test methods.
     *
     * <p>The returned stream is fully materialized before being returned; it is
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
                    } catch (IOException e) {
                        errors = true;
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
        return errors;
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
        List<String> lines = Files.readAllLines(file);
        String packageName = extractPackageName(lines);
        String fqcn = buildFqcn(file, root, packageName);
        String stem = buildFileStem(file, root);
        SourceContent content = () -> Optional.of(String.join(System.lineSeparator(), lines));

        for (int i = 0; i < lines.size(); i++) {
            Matcher m = TEST_FUNC_PATTERN.matcher(lines.get(i));
            if (m.find()) {
                String methodName = m.group(1);
                int beginLine = i + 1;
                int endLine = findFunctionEnd(lines, i);
                int loc = endLine - beginLine + 1;
                results.add(new DiscoveredMethod(
                        fqcn,
                        methodName,
                        beginLine,
                        endLine,
                        loc,
                        List.of(),
                        null,
                        file,
                        stem,
                        content));
            }
        }
    }

    // ── Package-private static helpers (testable) ──────────────────────────

    /**
     * Finds the one-based line number of the closing brace of the function
     * whose declaration starts at {@code startIdx} (zero-based).
     *
     * <p>Uses naive brace counting: increments depth for each {@code {}} found
     * and decrements for each {@code }}. Returns the line number of the line
     * that brings depth back to zero after the opening brace is seen.
     * If no closing brace is found, returns the last line of the file.</p>
     *
     * @param lines    all lines of the source file
     * @param startIdx zero-based index of the function declaration line
     * @return one-based line number of the closing {@code }}
     */
    /* default */ static int findFunctionEnd(List<String> lines, int startIdx) {
        int depth = 0;
        boolean seenOpen = false;
        for (int i = startIdx; i < lines.size(); i++) {
            String line = lines.get(i);
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') {
                    depth++;
                    seenOpen = true;
                } else if (ch == '}') {
                    depth--;
                    if (seenOpen && depth == 0) {
                        return i + 1;
                    }
                }
            }
        }
        return lines.size();
    }

    /**
     * Extracts the package name from the first {@code package} declaration
     * found in {@code lines}.
     *
     * @param lines all lines of a Go source file
     * @return the package name, or {@code "unknown"} if no declaration is found
     */
    /* default */ static String extractPackageName(List<String> lines) {
        for (String line : lines) {
            Matcher m = PACKAGE_PATTERN.matcher(line);
            if (m.find()) {
                return m.group(1);
            }
        }
        return "unknown";
    }

    /**
     * Derives the fully-qualified class name from the file's parent directory
     * relative to the scan root.
     *
     * <p>The path segments of the parent directory relative to {@code root}
     * are joined with {@code "."}.  If the file resides directly under
     * {@code root} (no parent segments) or relativization fails, {@code packageName}
     * is returned as the fallback.</p>
     *
     * @param file        path to the Go source file
     * @param root        scan root directory
     * @param packageName package name extracted from the file (used as fallback)
     * @return dot-separated identifier representing the file's directory path
     */
    /* default */ static String buildFqcn(Path file, Path root, String packageName) {
        try {
            Path relParent = root.relativize(file.getParent());
            if (relParent.getNameCount() == 0
                    || relParent.toString().isEmpty()) {
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
}
