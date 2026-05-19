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
import org.egothor.methodatlas.discovery.powershell.internal.CommandInfo;
import org.egothor.methodatlas.discovery.powershell.internal.PowerShellTestVisitor;
import org.egothor.methodatlas.discovery.powershell.parser.PowerShellTestLexer;
import org.egothor.methodatlas.discovery.powershell.parser.PowerShellTestParser;

/**
 * {@link TestDiscovery} implementation for PowerShell Pester test files.
 *
 * <p>Scans a directory root for {@code *.Tests.ps1} and {@code *.Test.ps1}
 * files (configurable via {@link TestDiscoveryConfig#fileSuffixesFor(String)}),
 * parses each with the ANTLR4-generated {@code PowerShellTest} grammar, and
 * emits one {@link DiscoveredMethod} per {@code It "..."} block found.</p>
 *
 * <h2>Pester constructs recognised</h2>
 * <ul>
 *   <li>{@code It "test name"} — the primary test block; single- or
 *       double-quoted; case-insensitive ({@code it}, {@code IT})</li>
 *   <li>{@code Describe "name"} — outer container block</li>
 *   <li>{@code Context "name"} — inner container block</li>
 *   <li>{@code -Tag "a","b"} and {@code -Tag @("a","b")} on the {@code It}
 *       line — tag extraction</li>
 * </ul>
 *
 * <h2>FQCN computation</h2>
 * <p>The FQCN is derived from the file path relative to the scan root:
 * directory segments are joined with {@code .} and the filename stem (with
 * {@code .Tests.ps1}, {@code .Test.ps1}, or {@code .ps1} stripped) forms the
 * final segment.  A file directly in the root yields only the filename stem.</p>
 *
 * <h2>Parser scope</h2>
 * <p>The {@code PowerShellTest} grammar is structural: it covers Pester
 * {@code Describe}, {@code Context}, and {@code It} blocks, treating all other
 * PowerShell content as opaque tokens.  Parse errors are logged at
 * {@code WARNING} level; ANTLR4 error recovery continues so that remaining
 * {@code It} blocks are still discovered.</p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>Registered via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.</p>
 *
 * @see TestDiscovery
 * @see DiscoveredMethod
 * @see PowerShellTestVisitor
 */
public final class PowerShellTestDiscovery implements TestDiscovery {

    private static final Logger LOG =
            Logger.getLogger(PowerShellTestDiscovery.class.getName());

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
     * <p>Reads {@link TestDiscoveryConfig#fileSuffixesFor(String)} for this
     * plugin.  When the resolved list is empty the default suffixes
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
     * Non-fatal per-file errors (e.g. parse failures) are logged at
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

    // ── Private helpers ────────────────────────────────────────────────────

    private boolean isPesterFile(Path path) {
        Path fn = path.getFileName();
        if (fn == null) {
            return false;
        }
        String name = fn.toString();
        return fileSuffixes.stream().anyMatch(name::endsWith);
    }

    private void discoverInFile(Path file, Path root,
                                List<DiscoveredMethod> results) throws IOException {
        PowerShellTestParser.ScriptContext tree = parse(file);
        if (tree == null) {
            return;
        }

        PowerShellTestVisitor visitor = new PowerShellTestVisitor();
        visitor.visit(tree);

        List<CommandInfo> commands = visitor.getDiscoveredCommands();
        if (commands.isEmpty()) {
            return;
        }

        String fqcn = buildFqcn(file, root);
        String stem = buildFileStem(file, root);
        SourceContent content = buildSourceContent(file);

        for (CommandInfo cmd : commands) {
            int loc = cmd.endLine() - cmd.beginLine() + 1;
            results.add(new DiscoveredMethod(
                    fqcn,
                    cmd.name(),
                    cmd.beginLine(),
                    cmd.endLine(),
                    loc,
                    cmd.tags(),
                    null,
                    file,
                    stem,
                    content));
        }
    }

    private PowerShellTestParser.ScriptContext parse(Path file) throws IOException {
        PowerShellTestLexer lexer = new PowerShellTestLexer(CharStreams.fromPath(file));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PowerShellTestParser parser = new PowerShellTestParser(tokens);
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
        PowerShellTestParser.ScriptContext tree = parser.script();
        if (!syntaxErrors.isEmpty()) {
            errors.set(true);
            if (LOG.isLoggable(Level.WARNING)) {
                syntaxErrors.forEach(err -> LOG.warning("PowerShell parse error: " + err));
            }
        }
        return tree;
    }

    private static SourceContent buildSourceContent(Path file) {
        return () -> {
            try {
                return Optional.of(Files.readString(file));
            } catch (IOException e) {
                return Optional.empty();
            }
        };
    }

    // ── Package-private static helpers (accessible from tests) ────────────

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
    static String stemOf(String filename) {
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
