package org.egothor.methodatlas.discovery.cobol;

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

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.discovery.cobol.internal.COBOLTestVisitor;
import org.egothor.methodatlas.discovery.cobol.internal.MethodInfo;
import org.egothor.methodatlas.discovery.cobol.parser.COBOLTestLexer;

/**
 * {@link TestDiscovery} implementation for COBOL source trees.
 *
 * <p>Supports two test conventions:</p>
 *
 * <ul>
 *   <li><b>Micro Focus MFUnit</b> ({@code .cbl}, {@code .cob},
 *       {@code .cobol} files) — paragraphs whose names start with
 *       {@code MFU-TC-} in the PROCEDURE DIVISION.  Each such paragraph
 *       is one test case.</li>
 *   <li><b>COBOL-Check</b> ({@code .cut} files, or mixed in {@code .cbl}
 *       files) — {@code TestCase 'name'} directives discovered by the
 *       ANTLR4 {@code COBOLTest} grammar.</li>
 * </ul>
 *
 * <h2>FQCN computation</h2>
 * <p>The FQCN is derived from the file path relative to the scan root
 * (dot-separated, extension stripped).  When the PROGRAM-ID can be
 * extracted, it is appended as the outermost qualifier.</p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>Registered via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.</p>
 *
 * @see TestDiscovery
 * @see DiscoveredMethod
 * @see COBOLTestVisitor
 */
public final class COBOLTestDiscovery implements TestDiscovery {

    private static final Logger LOG = Logger.getLogger(COBOLTestDiscovery.class.getName());

    private static final List<String> DEFAULT_SUFFIXES =
            List.of(".cbl", ".cob", ".cobol", ".cut");

    private List<String> fileSuffixes = DEFAULT_SUFFIXES;
    private final AtomicBoolean errors = new AtomicBoolean();

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     */
    public COBOLTestDiscovery() {
        // Required by ServiceLoader
    }

    @Override
    public String pluginId() {
        return "cobol";
    }

    /**
     * Configures file suffixes from the supplied {@link TestDiscoveryConfig}.
     *
     * @param config runtime configuration; never {@code null}
     */
    @Override
    public void configure(TestDiscoveryConfig config) {
        List<String> suffixes = config.fileSuffixesFor(pluginId());
        this.fileSuffixes = suffixes.isEmpty() ? DEFAULT_SUFFIXES : suffixes;
    }

    /**
     * Scans {@code root} for COBOL test files.
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
                .filter(this::isCobolFile)
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

    // ── Private helpers ───────────────────────────────────────────────

    private boolean isCobolFile(Path path) {
        Path fn = path.getFileName();
        if (fn == null) {
            return false;
        }
        String name = fn.toString();
        return fileSuffixes.stream().anyMatch(name::endsWith);
    }

    private void discoverInFile(Path file, Path root,
                                List<DiscoveredMethod> results) throws IOException {
        // COBOL discovery uses the lexer only — the scanner walks the raw
        // token stream so detection is robust against the wide variety of
        // statement-level constructs COBOL programs contain.
        COBOLTestLexer lexer = new COBOLTestLexer(CharStreams.fromPath(file));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        COBOLTestVisitor visitor = new COBOLTestVisitor();
        visitor.scan(tokens);

        List<MethodInfo> methods = visitor.getDiscoveredMethods();
        if (methods.isEmpty()) {
            return;
        }
        String stem = buildFileStem(file, root);
        SourceContent content = lazyContent(file);
        for (MethodInfo m : methods) {
            String fqcn = buildFqcn(stem, m.programId());
            int loc = m.endLine() - m.beginLine() + 1;
            results.add(new DiscoveredMethod(
                    fqcn, m.name(),
                    m.beginLine(), m.endLine(), loc,
                    List.of(), null, file, stem, content));
        }
    }

    private static SourceContent lazyContent(Path file) {
        return () -> {
            try {
                return Optional.of(Files.readString(file));
            } catch (IOException e) {
                return Optional.empty();
            }
        };
    }

    // ── Package-private static helpers (testable) ─────────────────────

    /**
     * Derives a dot-separated file stem from the file's path relative to
     * the scan root, stripping the file extension from the last segment.
     *
     * @param file path to the source file
     * @param root scan root directory
     * @return dot-separated stem; never {@code null} or empty
     */
    /* default */ static String buildFileStem(Path file, Path root) {
        Path rel = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            String part = rel.getName(i).toString();
            if (i == rel.getNameCount() - 1) {
                int dot = part.lastIndexOf('.');
                if (dot > 0) {
                    part = part.substring(0, dot);
                }
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * Builds the FQCN from the file stem and program ID.
     *
     * <p>When {@code programId} is {@code "unknown"} or blank, the stem
     * alone is returned.  Otherwise the stem is used as-is (the program ID
     * is typically embedded in the file stem).</p>
     *
     * @param stem      dot-separated file stem
     * @param programId PROGRAM-ID from the source file
     * @return FQCN string; never {@code null}
     */
    /* default */ static String buildFqcn(String stem, String programId) {
        if (programId == null || programId.isBlank() || "unknown".equalsIgnoreCase(programId)) {
            return stem;
        }
        return stem;
    }
}
