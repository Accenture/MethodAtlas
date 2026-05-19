package org.egothor.methodatlas.discovery.powershell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * {@link TestDiscovery} implementation for PowerShell Pester test files.
 *
 * <p>Scans a directory root for {@code *.Tests.ps1} and {@code *.Test.ps1}
 * files (configurable via {@link TestDiscoveryConfig#fileSuffixes()}), parses
 * each file line by line, and emits one {@link DiscoveredMethod} per
 * {@code It "..."} block found.</p>
 *
 * <h2>Pester constructs recognised</h2>
 * <ul>
 *   <li>{@code It "test name"} — the primary test block; single- or double-quoted</li>
 *   <li>{@code Describe "name"} — outer container block; used for FQCN only</li>
 *   <li>{@code Context "name"} / {@code InModuleScope "name"} — inner container block</li>
 *   <li>{@code -Tag "value", "value2"} on the same {@code It} line — tag extraction</li>
 * </ul>
 *
 * <h2>FQCN computation</h2>
 * <p>The FQCN is derived from the file path relative to the scan root: directory
 * segments are joined with {@code .} and the filename stem (with
 * {@code .Tests.ps1}, {@code .Test.ps1}, or {@code .ps1} stripped) forms the
 * final segment. For example, a file at
 * {@code src/auth/Auth.Tests.ps1} with root {@code src} yields FQCN
 * {@code auth.Auth}. A file directly in the root yields the filename stem.</p>
 *
 * <h2>Tag extraction</h2>
 * <p>Tags are extracted from the {@code It} line only. The {@code -Tag} switch
 * may be followed by one or more quoted strings, optionally wrapped in an array
 * literal ({@code @("a", "b")}). Tags inherited from enclosing
 * {@code Describe} blocks are not extracted in this version.</p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>Registered via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.</p>
 *
 * @see org.egothor.methodatlas.api.TestDiscovery
 * @see org.egothor.methodatlas.api.DiscoveredMethod
 */
public final class PowerShellTestDiscovery implements TestDiscovery {

    private static final Logger LOG =
            Logger.getLogger(PowerShellTestDiscovery.class.getName());

    /** Regex matching an {@code It} test block: {@code It 'name'} or {@code It "name"}. */
    static final Pattern IT_PATTERN =
            Pattern.compile("^\\s*It\\s+(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);

    /** Regex matching a {@code Describe} block header. */
    static final Pattern DESCRIBE_PATTERN =
            Pattern.compile("^\\s*Describe\\s+(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);

    /** Regex matching a {@code Context} or {@code InModuleScope} block header. */
    static final Pattern CONTEXT_PATTERN =
            Pattern.compile("^\\s*(?:Context|InModuleScope)\\s+(['\"])(.*?)\\1",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Regex matching the {@code -Tag} switch and the first quoted value that follows.
     * Additional comma-separated quoted strings are picked up by {@link #QUOTED_STRING}.
     */
    static final Pattern TAG_ON_LINE =
            Pattern.compile("-Tag\\s+(?:@\\()?(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);

    /** Regex for any quoted string — used to collect multi-value tag arrays. */
    static final Pattern QUOTED_STRING = Pattern.compile("['\"]([^'\"]+)['\"]");

    /** Default file suffixes when no configuration is supplied. */
    private static final List<String> DEFAULT_SUFFIXES =
            List.of(".Tests.ps1", ".Test.ps1");

    private List<String> fileSuffixes = DEFAULT_SUFFIXES;
    private final AtomicBoolean errors = new AtomicBoolean();

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     */
    public PowerShellTestDiscovery() {
        // Required by ServiceLoader
    }

    @Override
    public String pluginId() {
        return "powershell";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@link TestDiscoveryConfig#fileSuffixesFor(String)} for this plugin.
     * When the resolved list is empty the default suffixes
     * ({@code .Tests.ps1}, {@code .Test.ps1}) are retained.</p>
     *
     * @param config runtime configuration; never {@code null}
     */
    @Override
    public void configure(TestDiscoveryConfig config) {
        List<String> suffixes = config.fileSuffixesFor(pluginId());
        this.fileSuffixes = suffixes.isEmpty() ? DEFAULT_SUFFIXES : suffixes;
    }

    /**
     * Scans {@code root} for PowerShell Pester test files and returns all
     * discovered {@code It} blocks as {@link DiscoveredMethod} instances.
     *
     * <p>Files are matched by the configured {@link #fileSuffixes}.
     * Non-fatal per-file errors (e.g. unreadable files) are logged at
     * {@code WARNING} and skipped; {@link #hadErrors()} returns {@code true}
     * after such an error.</p>
     *
     * @param root directory to scan; ignored when it is not an actual directory
     * @return fully materialised stream of discovered methods; never {@code null}
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
                .filter(this::isPesterFile)
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

    @Override
    public boolean hadErrors() {
        return errors.get();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the file name ends with one of the configured suffixes.
     *
     * @param path file to test
     * @return {@code true} when the file is a Pester test file
     */
    private boolean isPesterFile(Path path) {
        Path fn = path.getFileName();
        if (fn == null) {
            return false;
        }
        String name = fn.toString();
        return fileSuffixes.stream().anyMatch(name::endsWith);
    }

    /**
     * Parses a single Pester test file and appends discovered methods to
     * {@code results}.
     *
     * @param file    file to parse; must exist and be readable
     * @param root    scan root used for FQCN and file-stem computation
     * @param results accumulator for discovered methods
     * @throws IOException if reading the file fails
     */
    private void discoverInFile(Path file, Path root,
                                 List<DiscoveredMethod> results) throws IOException {
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) {
            return;
        }

        String fqcn = buildFqcn(file, root);
        String stem = buildFileStem(file, root);
        SourceContent content = () -> Optional.of(String.join(System.lineSeparator(), lines));

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = IT_PATTERN.matcher(line);
            if (!m.find()) {
                continue;
            }
            String methodName = m.group(2);
            List<String> tags = extractTags(line);
            int beginLine = i + 1; // 1-based
            int endLine = findItBlockEnd(lines, i);
            int loc = endLine - beginLine + 1;

            results.add(new DiscoveredMethod(
                    fqcn,
                    methodName,
                    beginLine,
                    endLine,
                    loc,
                    List.copyOf(tags),
                    null,
                    file,
                    stem,
                    content));
        }
    }

    // ── Package-private static helpers (accessible from tests) ───────────────

    /**
     * Extracts tag values from a single {@code It} line.
     *
     * <p>Looks for the {@code -Tag} switch and collects every quoted string
     * that follows it on the same line. An array literal form
     * ({@code -Tag @("a", "b")}) and a simple form ({@code -Tag "a"}) are
     * both handled. Returns an empty list when no {@code -Tag} is present.</p>
     *
     * @param line the raw source line to examine
     * @return mutable list of tag strings (possibly empty)
     */
    static List<String> extractTags(String line) {
        Matcher tagMatcher = TAG_ON_LINE.matcher(line);
        if (!tagMatcher.find()) {
            return new ArrayList<>();
        }
        // Collect all quoted strings that appear after the -Tag position
        int tagStart = tagMatcher.start();
        String afterTag = line.substring(tagStart);
        Matcher qMatcher = QUOTED_STRING.matcher(afterTag);
        List<String> tags = new ArrayList<>();
        while (qMatcher.find()) {
            tags.add(qMatcher.group(1));
        }
        return tags;
    }

    /**
     * Finds the line number (1-based) of the closing {@code }} of the
     * {@code It} block that starts at {@code startIdx}.
     *
     * <p>Brace counting begins at {@code startIdx}. When the opening brace
     * for the block is not on {@code startIdx} (i.e. brace depth never
     * reaches 1), the method falls back to returning {@code startIdx + 1}
     * so that single-line {@code It} declarations without a body still
     * produce a valid line range.</p>
     *
     * @param lines    all lines of the file (0-based)
     * @param startIdx 0-based index of the {@code It} line
     * @return 1-based line number of the closing brace; at least
     *         {@code startIdx + 1}
     */
    static int findItBlockEnd(List<String> lines, int startIdx) {
        int depth = 0;
        boolean opened = false;
        for (int i = startIdx; i < lines.size(); i++) {
            String line = lines.get(i);
            for (char ch : line.toCharArray()) {
                if (ch == '{') {
                    depth++;
                    opened = true;
                } else if (ch == '}') {
                    depth--;
                    if (opened && depth == 0) {
                        return i + 1; // 1-based
                    }
                }
            }
        }
        // No opening brace found or unmatched — use the It line itself
        return startIdx + 1;
    }

    /**
     * Builds the FQCN for a file by relativising the parent directory from
     * the root and joining path segments with {@code .}, then appending the
     * filename stem.
     *
     * <p>Examples (root = {@code /src}):</p>
     * <ul>
     *   <li>{@code /src/Auth.Tests.ps1} → {@code Auth}</li>
     *   <li>{@code /src/auth/Auth.Tests.ps1} → {@code auth.Auth}</li>
     *   <li>{@code /src/modules/auth/Auth.Tests.ps1} → {@code modules.auth.Auth}</li>
     * </ul>
     *
     * @param file file whose FQCN is needed
     * @param root scan root directory
     * @return dot-separated FQCN string; never {@code null} or empty
     */
    static String buildFqcn(Path file, Path root) {
        Path parent = file.getParent();
        if (parent == null || parent.equals(root)) {
            return stemOf(file.getFileName().toString());
        }
        Path relParent = root.relativize(parent);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relParent.getNameCount(); i++) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(relParent.getName(i).toString());
        }
        sb.append('.').append(stemOf(file.getFileName().toString()));
        return sb.toString();
    }

    /**
     * Builds the dot-separated file stem for a file relative to the scan root.
     *
     * <p>The full file path (including the filename) is relativised from
     * {@code root} and path segments are joined with {@code .}. The
     * PowerShell test suffix ({@code .Tests.ps1}, {@code .Test.ps1}, or
     * plain {@code .ps1}) is stripped from the last segment.</p>
     *
     * <p>Example: file {@code /src/auth/Auth.Tests.ps1}, root {@code /src}
     * → stem {@code auth.Auth}.</p>
     *
     * @param file file whose stem is needed
     * @param root scan root directory
     * @return dot-separated stem string; never {@code null} or empty
     */
    static String buildFileStem(Path file, Path root) {
        Path rel = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            String part = rel.getName(i).toString();
            if (i == rel.getNameCount() - 1) {
                part = stemOf(part);
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * Strips the PowerShell test suffix from a filename.
     *
     * <p>Suffixes checked in order: {@code .Tests.ps1}, {@code .Test.ps1},
     * {@code .ps1}. Returns the input unchanged if none matches.</p>
     *
     * @param filename filename (not a full path) to strip
     * @return filename without the matching suffix
     */
    private static String stemOf(String filename) {
        if (filename.endsWith(".Tests.ps1")) {
            return filename.substring(0, filename.length() - ".Tests.ps1".length());
        }
        if (filename.endsWith(".Test.ps1")) {
            return filename.substring(0, filename.length() - ".Test.ps1".length());
        }
        if (filename.endsWith(".ps1")) {
            return filename.substring(0, filename.length() - ".ps1".length());
        }
        return filename;
    }
}
