package org.egothor.methodatlas.discovery.abap;

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
import org.egothor.methodatlas.discovery.abap.internal.ABAPTestVisitor;
import org.egothor.methodatlas.discovery.abap.internal.ECATTScriptVisitor;
import org.egothor.methodatlas.discovery.abap.internal.MethodInfo;
import org.egothor.methodatlas.discovery.abap.parser.ABAPTestLexer;
import org.egothor.methodatlas.discovery.abap.parser.ECATTScriptLexer;
import org.egothor.methodatlas.discovery.abap.parser.ECATTScriptParser;

/**
 * {@link TestDiscovery} implementation for SAP ABAP source trees.
 *
 * <p>Scans for two file types and test conventions:</p>
 *
 * <ul>
 *   <li><b>ABAP Unit</b> ({@code .abap} files) — classes annotated with
 *       {@code FOR TESTING} whose methods are also marked {@code FOR TESTING}
 *       via the ABAP Unit framework.  The ANTLR4 {@code ABAPTest} grammar
 *       is used for structural parsing.</li>
 *   <li><b>ecATT</b> ({@code .ecl} files) — exported ecATT script files
 *       produced by SAP transaction {@code SECATT}.  Each {@code FUNCTION}
 *       block is treated as one test case.  The ANTLR4 {@code ECATTScript}
 *       grammar is used.</li>
 * </ul>
 *
 * <h2>FQCN computation</h2>
 * <p>For ABAP Unit: the fully-qualified class name (e.g. {@code ZCL_AUTH_TEST})
 * is used as the FQCN.  For ecATT: the script function name is used.</p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>Registered via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.</p>
 *
 * @see TestDiscovery
 * @see DiscoveredMethod
 * @see ABAPTestVisitor
 * @see ECATTScriptVisitor
 */
public final class ABAPTestDiscovery implements TestDiscovery {

    private static final Logger LOG = Logger.getLogger(ABAPTestDiscovery.class.getName());

    private static final String DEFAULT_ABAP_SUFFIX = ".abap";
    private static final String DEFAULT_ECATT_SUFFIX = ".ecl";

    private List<String> abapSuffixes  = List.of(DEFAULT_ABAP_SUFFIX);
    private List<String> ecattSuffixes = List.of(DEFAULT_ECATT_SUFFIX);
    private final AtomicBoolean errors = new AtomicBoolean();

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     */
    public ABAPTestDiscovery() {
        // Required by ServiceLoader
    }

    @Override
    public String pluginId() {
        return "abap";
    }

    /**
     * Configures file suffixes from the supplied {@link TestDiscoveryConfig}.
     *
     * <p>Reads {@code fileSuffixesFor("abap")} for ABAP Unit files and
     * {@code fileSuffixesFor("ecatt")} for ecATT files.  Falls back to
     * {@code .abap} and {@code .ecl} respectively when not configured.</p>
     *
     * @param config runtime configuration; never {@code null}
     */
    @Override
    public void configure(TestDiscoveryConfig config) {
        List<String> abap  = config.fileSuffixesFor(pluginId());
        List<String> ecatt = config.fileSuffixesFor("ecatt");
        this.abapSuffixes  = abap.isEmpty()  ? List.of(DEFAULT_ABAP_SUFFIX)  : abap;
        this.ecattSuffixes = ecatt.isEmpty() ? List.of(DEFAULT_ECATT_SUFFIX) : ecatt;
    }

    /**
     * Scans {@code root} for ABAP Unit and ecATT test files.
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
                .forEach(file -> {
                    try {
                        if (isAbapFile(file)) {
                            discoverAbapUnit(file, root, results);
                        } else if (isEcattFile(file)) {
                            discoverEcatt(file, root, results);
                        }
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

    // ── Private: ABAP Unit ────────────────────────────────────────────

    private void discoverAbapUnit(Path file, Path root,
                                  List<DiscoveredMethod> results) throws IOException {
        // ABAP discovery uses the lexer only — the visitor walks the raw
        // token stream so the discovery is robust against the wide variety
        // of statement-level constructs that appear inside method bodies
        // and class headers.
        ABAPTestLexer lexer = new ABAPTestLexer(CharStreams.fromPath(file));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        ABAPTestVisitor visitor = new ABAPTestVisitor();
        visitor.scan(tokens);

        List<MethodInfo> methods = visitor.getDiscoveredMethods();
        if (methods.isEmpty()) {
            return;
        }
        SourceContent content = lazyContent(file);
        String stem = buildFileStem(file, root);
        for (MethodInfo m : methods) {
            int loc = m.endLine() - m.beginLine() + 1;
            results.add(new DiscoveredMethod(
                    m.className(), m.name(),
                    m.beginLine(), m.endLine(), loc,
                    List.of(), null, file, stem, content));
        }
    }

    // ── Private: ecATT ────────────────────────────────────────────────

    private void discoverEcatt(Path file, Path root,
                               List<DiscoveredMethod> results) throws IOException {
        ECATTScriptLexer lexer = new ECATTScriptLexer(CharStreams.fromPath(file));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        ECATTScriptParser parser = new ECATTScriptParser(tokens);
        parser.removeErrorListeners();
        addEcattSyntaxErrorListener(parser, file);

        ECATTScriptParser.SourceFileContext tree = parser.sourceFile();
        ECATTScriptVisitor visitor = new ECATTScriptVisitor();
        visitor.visit(tree);

        List<MethodInfo> methods = visitor.getDiscoveredMethods();
        if (methods.isEmpty()) {
            return;
        }
        SourceContent content = lazyContent(file);
        String stem = buildFileStem(file, root);
        for (MethodInfo m : methods) {
            int loc = m.endLine() - m.beginLine() + 1;
            results.add(new DiscoveredMethod(
                    m.name(), m.name(),
                    m.beginLine(), m.endLine(), loc,
                    List.of(), null, file, stem, content));
        }
    }

    // ── Private: helpers ──────────────────────────────────────────────

    private boolean isAbapFile(Path path) {
        Path fn = path.getFileName();
        if (fn == null) {
            return false;
        }
        String name = fn.toString();
        return abapSuffixes.stream().anyMatch(name::endsWith);
    }

    private boolean isEcattFile(Path path) {
        Path fn = path.getFileName();
        if (fn == null) {
            return false;
        }
        String name = fn.toString();
        return ecattSuffixes.stream().anyMatch(name::endsWith);
    }

    private void addEcattSyntaxErrorListener(ECATTScriptParser parser, Path file) {
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine,
                                    String msg, RecognitionException e) {
                errors.set(true);
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.warning("ecATT parse error: " + file + ":" + line + ":"
                            + charPositionInLine + ": " + msg);
                }
            }
        });
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
}
