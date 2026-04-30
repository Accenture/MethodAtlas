package org.egothor.methodatlas.discovery.dotnet;

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

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.discovery.dotnet.internal.AttributeInfo;
import org.egothor.methodatlas.discovery.dotnet.internal.CSharpTestVisitor;
import org.egothor.methodatlas.discovery.dotnet.internal.FrameworkKind;
import org.egothor.methodatlas.discovery.dotnet.internal.MethodInfo;
import org.egothor.methodatlas.discovery.dotnet.parser.CSharpTestLexer;
import org.egothor.methodatlas.discovery.dotnet.parser.CSharpTestParser;

/**
 * {@link TestDiscovery} implementation for C# source trees.
 *
 * <p>Scans a directory root for {@code .cs} files (configurable via
 * {@link TestDiscoveryConfig#fileSuffixes()}), parses each with the
 * ANTLR4-generated {@code CSharpTest} grammar, and emits one
 * {@link DiscoveredMethod} per test method found.</p>
 *
 * <p>Test-framework detection is automatic from {@code using} directives:
 * xUnit ({@code Xunit.*}), NUnit ({@code NUnit.*}), and MSTest
 * ({@code Microsoft.VisualStudio.TestTools.*}) are all supported.
 * Test markers can be overridden via
 * {@link TestDiscoveryConfig#testMarkers()}.</p>
 *
 * <h2>Tag extraction</h2>
 * <ul>
 *   <li>NUnit — {@code [Category("value")]}</li>
 *   <li>xUnit — {@code [Trait("Tag", "value")]} or
 *       {@code [Trait("Category", "value")]}</li>
 *   <li>MSTest — {@code [TestCategory("value")]}</li>
 * </ul>
 *
 * <h2>Display-name extraction</h2>
 * <p>xUnit only: {@code [Fact(DisplayName = "text")]} /
 * {@code [Theory(DisplayName = "text")]}. NUnit and MSTest do not have a
 * standard display-name attribute and return {@code null}.</p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>Registered via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.</p>
 *
 * @see DotNetSourcePatcher
 */
public final class DotNetTestDiscovery implements TestDiscovery {

    private static final Logger LOG = Logger.getLogger(DotNetTestDiscovery.class.getName());

    private List<String> fileSuffixes = List.of(".cs");
    private Set<String>  testMarkers  = Set.of();
    private volatile boolean errors;

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     */
    public DotNetTestDiscovery() {
        // Required by ServiceLoader
    }

    @Override
    public String pluginId() {
        return "dotnet";
    }

    @Override
    public void configure(TestDiscoveryConfig config) {
        List<String> suffixes = config.fileSuffixesFor(pluginId());
        this.fileSuffixes = suffixes.isEmpty() ? List.of(".cs") : suffixes;
        this.testMarkers = Set.copyOf(config.testMarkers());
    }

    @Override
    public Stream<DiscoveredMethod> discover(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        List<DiscoveredMethod> results = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(this::isCSharpFile)
                .forEach(file -> {
                    try {
                        discoverInFile(file, root, results);
                    } catch (Exception e) {
                        errors = true;
                        LOG.log(Level.WARNING, "Failed to process: " + file, e);
                    }
                });
        }
        return results.stream();
    }

    @Override
    public boolean hadErrors() {
        return errors;
    }

    // ── Private helpers ───────────────────────────────────────────────

    private boolean isCSharpFile(Path path) {
        Path fn = path.getFileName();
        if (fn == null) return false;
        String name = fn.toString();
        return fileSuffixes.stream().anyMatch(name::endsWith);
    }

    private void discoverInFile(Path file, Path root,
                                 List<DiscoveredMethod> results) throws IOException {
        CSharpTestParser.CompilationUnitContext tree = parse(file);
        if (tree == null) return;

        CSharpTestVisitor visitor = new CSharpTestVisitor(testMarkers);
        visitor.visit(tree);

        FrameworkKind framework = visitor.getFramework();
        List<MethodInfo> methods = visitor.getDiscoveredMethods();
        if (methods.isEmpty()) return;

        String stem = buildFileStem(file, root);
        // Shared SourceContent for all methods in this file
        SourceContent content = buildSourceContent(file);

        for (MethodInfo m : methods) {
            results.add(buildDiscoveredMethod(m, file, stem, content, framework));
        }
    }

    private DiscoveredMethod buildDiscoveredMethod(MethodInfo m, Path file,
                                                    String stem,
                                                    SourceContent content,
                                                    FrameworkKind fw) {
        List<String> tags    = extractTags(m, fw);
        String       dispName = extractDisplayName(m, fw);
        int          loc      = m.endLine() - m.beginLine() + 1;

        return new DiscoveredMethod(
                m.fqcn(),
                m.methodName(),
                m.beginLine(),
                m.endLine(),
                loc,
                tags,
                dispName,
                file,
                stem,
                content);
    }

    private List<String> extractTags(MethodInfo m, FrameworkKind fw) {
        Set<String> tagAttrNames = fw.tagAttributeNames();
        List<String> tags = new ArrayList<>();
        for (AttributeInfo attr : m.attributes()) {
            if (!tagAttrNames.contains(attr.simpleName())) continue;
            switch (fw) {
                case XUNIT -> {
                    // [Trait("Tag", "value")] or [Trait("Category", "value")]
                    List<String> pos = attr.positionalArgs();
                    if (pos.size() >= 2 && pos.get(1) != null) {
                        String key = pos.get(0);
                        if ("Tag".equalsIgnoreCase(key) || "Category".equalsIgnoreCase(key)) {
                            tags.add(pos.get(1));
                        }
                    }
                }
                case NUNIT, MSTEST, UNKNOWN -> {
                    // [Category("value")] or [TestCategory("value")]
                    List<String> pos = attr.positionalArgs();
                    if (!pos.isEmpty() && pos.get(0) != null) {
                        tags.add(pos.get(0));
                    }
                }
                default -> { /* no-op */ }
            }
        }
        return List.copyOf(tags);
    }

    private String extractDisplayName(MethodInfo m, FrameworkKind fw) {
        if (!fw.supportsDisplayName()) return null;
        // xUnit: [Fact(DisplayName = "text")] or [Theory(DisplayName = "text")]
        for (AttributeInfo attr : m.attributes()) {
            if ("Fact".equals(attr.simpleName()) || "Theory".equals(attr.simpleName())) {
                return attr.namedArgs().get("DisplayName");  // null if absent = no annotation
            }
        }
        return null;
    }

    private CSharpTestParser.CompilationUnitContext parse(Path file) throws IOException {
        CSharpTestLexer lexer = new CSharpTestLexer(CharStreams.fromPath(file));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        CSharpTestParser parser = new CSharpTestParser(tokens);
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
        CSharpTestParser.CompilationUnitContext tree = parser.compilationUnit();
        if (!syntaxErrors.isEmpty()) {
            errors = true;
            syntaxErrors.forEach(err -> LOG.warning("C# parse error: " + err));
        }
        return tree;
    }

    private static String buildFileStem(Path file, Path root) {
        Path rel = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (!sb.isEmpty()) sb.append('.');
            String part = rel.getName(i).toString();
            // drop .cs extension from last segment
            if (i == rel.getNameCount() - 1 && part.endsWith(".cs")) {
                part = part.substring(0, part.length() - 3);
            }
            sb.append(part);
        }
        return sb.toString();
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
}
