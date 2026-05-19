package org.egothor.methodatlas.discovery.python;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

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

/**
 * Discovers Python test functions and methods in pytest-convention source files.
 *
 * <p>
 * Two pytest file-naming conventions are supported by default:
 * </p>
 * <ul>
 *   <li>Files whose name starts with {@code "test_"} and ends with {@code ".py"}
 *       (e.g. {@code test_auth.py}).</li>
 *   <li>Files whose name ends with {@code "_test.py"}
 *       (e.g. {@code security_test.py}).</li>
 * </ul>
 *
 * <p>
 * If the caller supplies suffixes via
 * {@link TestDiscoveryConfig#fileSuffixesFor(String) fileSuffixesFor("python")},
 * those suffixes are used for the suffix check in addition to the built-in
 * {@code "test_"} prefix check, which is always active.
 * </p>
 *
 * <h2>Parsing</h2>
 *
 * <p>
 * Parsing is performed by a line-by-line state machine.  No external parsing
 * library is required.  The state machine recognises:
 * </p>
 * <ul>
 *   <li>{@code class Test*} / {@code class *Test} / {@code class *Tests} —
 *       enter class scope</li>
 *   <li>{@code def test_*()} / {@code async def test_*()} — function / method
 *       to emit</li>
 *   <li>{@code @pytest.mark.<name>} — collect tag names accumulated before the
 *       next {@code def}</li>
 * </ul>
 *
 * <h2>ServiceLoader registration</h2>
 *
 * <p>
 * This class is registered in
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery} so that
 * it is loaded automatically by the orchestration layer via
 * {@link java.util.ServiceLoader}.
 * </p>
 *
 * @see TestDiscovery
 * @see DiscoveredMethod
 */
public final class PythonTestDiscovery implements TestDiscovery {

    private static final Logger LOG =
            Logger.getLogger(PythonTestDiscovery.class.getName());

    /** Regex that matches a pytest.mark decorator line. */
    private static final Pattern MARK_PATTERN =
            Pattern.compile("^\\s*@pytest\\.mark\\.(\\w+)");

    /** Regex that matches a class definition line. */
    private static final Pattern CLASS_PATTERN =
            Pattern.compile("^\\s*class\\s+(\\w+)");

    /**
     * Regex that matches a function definition (sync or async),
     * where the function name starts with {@code test_}.
     */
    private static final Pattern DEF_PATTERN =
            Pattern.compile("^\\s*(?:async\\s+)?def\\s+(test_\\w+)\\s*\\(");

    /** Regex that matches a decorator line (starts with {@code @}). */
    private static final Pattern DECORATOR_PATTERN =
            Pattern.compile("^\\s*@");

    private List<String> configSuffixes = List.of();
    private boolean hadErrors;

    /**
     * Returns the unique identifier for this provider.
     *
     * @return {@code "python"}
     */
    @Override
    public String pluginId() {
        return "python";
    }

    /**
     * Configures this provider with the runtime {@link TestDiscoveryConfig}.
     *
     * <p>
     * Suffix entries targeted at {@code "python"} (or global entries) are
     * extracted via {@link TestDiscoveryConfig#fileSuffixesFor(String)} and
     * stored for use during file selection.
     * </p>
     *
     * @param config runtime configuration; never {@code null}
     */
    @Override
    public void configure(TestDiscoveryConfig config) {
        configSuffixes = config.fileSuffixesFor(pluginId());
    }

    /**
     * Scans {@code root} recursively and returns a fully materialised stream of
     * discovered test methods found in Python test files.
     *
     * <p>
     * Non-fatal per-file errors (e.g. read failures) are logged and skipped;
     * {@link #hadErrors()} returns {@code true} after such a run.
     * </p>
     *
     * @param root the directory to scan; must exist and be a directory
     * @return stream of discovered test methods; never {@code null}
     * @throws IOException if the file tree cannot be traversed
     */
    @Override
    public Stream<DiscoveredMethod> discover(Path root) throws IOException {
        hadErrors = false;
        List<DiscoveredMethod> results = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> isPythonTestFile(p.getFileName().toString(), configSuffixes))
                .forEach(file -> processFile(file, root, results));
        }

        return results.stream();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hadErrors() {
        return hadErrors;
    }

    // -------------------------------------------------------------------------
    // File processing
    // -------------------------------------------------------------------------

    /**
     * Reads {@code file}, parses it, and appends any discovered methods to
     * {@code results}. Logs and sets {@link #hadErrors} on any I/O failure.
     *
     * @param file    the source file to parse
     * @param root    the scan root (used to compute the module path)
     * @param results accumulator for discovered methods
     */
    private void processFile(Path file, Path root, List<DiscoveredMethod> results) {
        try {
            List<String> lines = Files.readAllLines(file);
            String modulePath = buildModulePath(file, root);
            SourceContent sourceContent = buildSourceContent(lines);
            parseLines(lines, file, modulePath, sourceContent, results);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read Python file: " + file, e);
            hadErrors = true;
        }
    }

    /**
     * Parses the given lines with a line-by-line state machine and appends
     * any discovered test methods to {@code results}.
     *
     * @param lines         source lines of the file
     * @param file          absolute path of the source file
     * @param modulePath    dot-separated module path for the file
     * @param sourceContent lazy source-content provider shared by all methods
     * @param results       accumulator for discovered methods
     */
    private void parseLines(
            List<String> lines,
            Path file,
            String modulePath,
            SourceContent sourceContent,
            List<DiscoveredMethod> results) {

        String currentClass = null;
        int classIndent = -1;
        List<String> pendingTags = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (isBlankOrComment(line)) {
                continue;
            }

            int indent = countIndent(line);

            // Exit class scope when a non-decorator, non-blank line is at or
            // before the class indent level.
            if (currentClass != null
                    && indent <= classIndent
                    && !isDecorator(line)) {
                currentClass = null;
                classIndent = -1;
            }

            Matcher markMatcher = MARK_PATTERN.matcher(line);
            if (markMatcher.find()) {
                pendingTags.add(markMatcher.group(1));
                continue;
            }

            Matcher classMatcher = CLASS_PATTERN.matcher(line);
            if (classMatcher.find()) {
                pendingTags.clear();
                String name = classMatcher.group(1);
                if (isTestClassName(name)) {
                    currentClass = name;
                    classIndent = indent;
                }
                continue;
            }

            Matcher defMatcher = DEF_PATTERN.matcher(line);
            if (defMatcher.find()) {
                String methodName = defMatcher.group(1);
                int funcIndent = indent;
                int beginLine = i + 1;
                int endLine = findFunctionEnd(lines, i, funcIndent);
                int loc = endLine - beginLine + 1;
                String fqcn = currentClass != null
                        ? modulePath + "." + currentClass
                        : modulePath;
                DiscoveredMethod method = new DiscoveredMethod(
                        fqcn,
                        methodName,
                        beginLine,
                        endLine,
                        loc,
                        List.copyOf(pendingTags),
                        null,
                        file,
                        modulePath,
                        sourceContent);
                results.add(method);
                pendingTags.clear();
                continue;
            }

            // Any other non-decorator, non-blank, non-comment line clears tags.
            pendingTags.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (accessible from tests)
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the given file name should be scanned for
     * Python test functions.
     *
     * <p>
     * Selection rules (applied in order):
     * </p>
     * <ol>
     *   <li>If the name starts with {@code "test_"} and ends with
     *       {@code ".py"} → accept (always active, regardless of configured
     *       suffixes).</li>
     *   <li>If {@code configuredSuffixes} is non-empty: accept if the name
     *       ends with any of those suffixes.</li>
     *   <li>Otherwise (empty configured suffixes): accept if the name ends
     *       with the default suffix {@code "_test.py"}.</li>
     * </ol>
     *
     * @param fileName          the simple file name (no directory component)
     * @param configuredSuffixes suffixes from {@link TestDiscoveryConfig#fileSuffixesFor};
     *                          an empty list means "use defaults"
     * @return {@code true} if the file should be scanned
     */
    /* default */ static boolean isPythonTestFile(
            String fileName, List<String> configuredSuffixes) {
        // The test_ prefix check is always active.
        if (fileName.startsWith("test_") && fileName.endsWith(".py")) {
            return true;
        }
        if (!configuredSuffixes.isEmpty()) {
            return configuredSuffixes.stream().anyMatch(fileName::endsWith);
        }
        return fileName.endsWith("_test.py");
    }

    /**
     * Computes the dot-separated module path for {@code file} relative to
     * {@code root}.
     *
     * <p>
     * Both paths are normalised before relativising.  Path segments are joined
     * with {@code "."} and the {@code ".py"} extension is stripped from the
     * last segment.
     * </p>
     *
     * <p>
     * Example: if {@code root} is {@code /project/tests} and {@code file} is
     * {@code /project/tests/auth/test_auth.py}, the result is
     * {@code "auth.test_auth"}.
     * </p>
     *
     * @param file the source file; must be inside {@code root}
     * @param root the scan root
     * @return dot-separated module path; never {@code null} or empty
     */
    /* default */ static String buildModulePath(Path file, Path root) {
        Path relative = root.normalize().relativize(file.normalize());
        int count = relative.getNameCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String segment = relative.getName(i).toString();
            if (i == count - 1) {
                // Strip .py extension from the last segment.
                int dot = segment.lastIndexOf('.');
                if (dot > 0) {
                    segment = segment.substring(0, dot);
                }
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(segment);
        }
        return sb.toString();
    }

    /**
     * Finds the one-based line number of the last line belonging to the
     * function that starts at {@code startIdx}.
     *
     * <p>
     * The algorithm scans forward from {@code startIdx + 1}, skipping blank
     * and comment lines.  Whenever a non-blank, non-comment line is found with
     * an indent level greater than {@code funcIndent}, it is recorded as the
     * last body line.  When a non-blank, non-comment line at or before
     * {@code funcIndent} is found, the scan stops.
     * </p>
     *
     * @param lines     all source lines of the file
     * @param startIdx  zero-based index of the {@code def} line
     * @param funcIndent indent level of the {@code def} keyword
     * @return one-based line number of the last line of the function body;
     *         equals {@code startIdx + 1} (the {@code def} line itself)
     *         when the body is empty
     */
    /* default */ static int findFunctionEnd(
            List<String> lines, int startIdx, int funcIndent) {
        int lastBodyLine = startIdx; // 0-based; def line is the minimum
        for (int i = startIdx + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isBlankOrComment(line)) {
                continue;
            }
            int indent = countIndent(line);
            if (indent <= funcIndent) {
                break;
            }
            lastBodyLine = i;
        }
        return lastBodyLine + 1; // convert to 1-based
    }

    // -------------------------------------------------------------------------
    // Private utilities
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code line} is blank or a Python comment.
     *
     * @param line source line (may contain leading whitespace)
     * @return {@code true} for blank or {@code #}-comment lines
     */
    private static boolean isBlankOrComment(String line) {
        String trimmed = line.strip();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    /**
     * Returns {@code true} if {@code line} starts with a decorator marker
     * ({@code @}).
     *
     * @param line source line
     * @return {@code true} when the stripped line begins with {@code @}
     */
    private static boolean isDecorator(String line) {
        return DECORATOR_PATTERN.matcher(line).find();
    }

    /**
     * Counts the number of leading space or tab characters in {@code line}.
     * Tabs are counted as one character each (consistent with CPython indentation
     * counting for detecting scope changes).
     *
     * @param line a source line
     * @return number of leading whitespace characters
     */
    /* default */ static int countIndent(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Returns {@code true} when {@code className} follows pytest's test-class
     * naming conventions.
     *
     * @param className simple class name
     * @return {@code true} for names starting with {@code "Test"}, ending
     *         with {@code "Test"}, or ending with {@code "Tests"}
     */
    private static boolean isTestClassName(String className) {
        return className.startsWith("Test")
                || className.endsWith("Test")
                || className.endsWith("Tests");
    }

    /**
     * Builds a {@link SourceContent} that lazily returns the file text from
     * the pre-read {@code lines} list.
     *
     * @param lines source lines already read from the file
     * @return a {@link SourceContent} that joins lines with the system line
     *         separator
     */
    private static SourceContent buildSourceContent(List<String> lines) {
        return () -> Optional.of(String.join(System.lineSeparator(), lines));
    }
}
