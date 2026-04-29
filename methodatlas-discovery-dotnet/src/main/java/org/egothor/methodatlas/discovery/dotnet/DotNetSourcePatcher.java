package org.egothor.methodatlas.discovery.dotnet;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.discovery.dotnet.internal.AttributeInfo;
import org.egothor.methodatlas.discovery.dotnet.internal.CSharpTestVisitor;
import org.egothor.methodatlas.discovery.dotnet.internal.FrameworkKind;
import org.egothor.methodatlas.discovery.dotnet.internal.MethodInfo;
import org.egothor.methodatlas.discovery.dotnet.parser.CSharpTestLexer;
import org.egothor.methodatlas.discovery.dotnet.parser.CSharpTestParser;

/**
 * {@link SourcePatcher} implementation for C# source files.
 *
 * <p>Applies tag and display-name annotations back into {@code .cs} source
 * files. Tag attributes are written using the appropriate syntax for the
 * detected test framework:</p>
 * <ul>
 *   <li>NUnit — {@code [Category("value")]}</li>
 *   <li>xUnit — {@code [Trait("Tag", "value")]}</li>
 *   <li>MSTest — {@code [TestCategory("value")]}</li>
 * </ul>
 *
 * <p>Display names are written only for xUnit methods as a {@code DisplayName}
 * named parameter of {@code [Fact]} / {@code [Theory]}.
 * NUnit and MSTest methods are left unchanged for display names.</p>
 *
 * <p>Source files are patched using line-oriented text replacement so that all
 * formatting outside the modified attribute lines is preserved exactly.</p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>Registered via
 * {@code META-INF/services/org.egothor.methodatlas.api.SourcePatcher}.</p>
 *
 * @see DotNetTestDiscovery
 */
public final class DotNetSourcePatcher implements SourcePatcher {

    private static final Logger LOG = Logger.getLogger(DotNetSourcePatcher.class.getName());

    private List<String> fileSuffixes = List.of(".cs");
    private Set<String>  testMarkers  = Set.of();

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     */
    public DotNetSourcePatcher() {
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
    public boolean supports(Path sourceFile) {
        Path fn = sourceFile.getFileName();
        if (fn == null) return false;
        String name = fn.toString();
        return fileSuffixes.stream().anyMatch(name::endsWith);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Parses the source file, locates each test method listed in
     * {@code tagsToApply} or {@code displayNames}, and applies the desired
     * annotation state. Changes are written back in-place using line-based
     * text replacement.</p>
     *
     * @return number of attribute changes made; {@code 0} if the file was not
     *         modified
     */
    @Override
    public int patch(Path sourceFile,
                     Map<String, List<String>> tagsToApply,
                     Map<String, String> displayNames,
                     PrintWriter diagnostics) throws IOException {

        if (tagsToApply.isEmpty() && displayNames.isEmpty()) {
            return 0;
        }

        CSharpTestParser.CompilationUnitContext tree = parse(sourceFile);
        if (tree == null) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("DotNetSourcePatcher: failed to parse " + sourceFile);
            }
            return 0;
        }

        CSharpTestVisitor visitor = new CSharpTestVisitor(testMarkers);
        visitor.visit(tree);

        FrameworkKind framework = visitor.getFramework();
        List<String> lines = new ArrayList<>(
                Files.readAllLines(sourceFile, StandardCharsets.UTF_8));

        int totalChanges = 0;
        // Process methods in reverse line order so earlier insertions don't
        // shift the line numbers of later methods.
        List<MethodInfo> methods = new ArrayList<>(visitor.getDiscoveredMethods());
        methods.sort((a, b) -> Integer.compare(b.beginLine(), a.beginLine()));

        for (MethodInfo method : methods) {
            String name = method.methodName();
            if (!tagsToApply.containsKey(name) && !displayNames.containsKey(name)) {
                continue;
            }
            List<String> desiredTags = tagsToApply.get(name);
            String desiredDisplayName = displayNames.get(name);

            int changes = applyMethod(lines, method, framework,
                                      desiredTags, desiredDisplayName);
            totalChanges += changes;
        }

        if (totalChanges > 0) {
            // Reconstruct file: join lines with system line separator, preserving
            // a trailing newline if the original had one.
            String nl = detectLineSeparator(sourceFile);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                sb.append(lines.get(i));
                if (i < lines.size() - 1) sb.append(nl);
            }
            // Preserve trailing newline from original
            String original = Files.readString(sourceFile, StandardCharsets.UTF_8);
            if (original.endsWith("\n") || original.endsWith("\r\n")) {
                sb.append(nl);
            }
            Files.writeString(sourceFile, sb.toString(), StandardCharsets.UTF_8);
            diagnostics.println("Patched: " + sourceFile + " (+" + totalChanges + " change(s))");
        }
        return totalChanges;
    }

    // ── Core patching logic ───────────────────────────────────────────

    /**
     * Applies desired tag and display-name state to one method in the
     * (mutable) {@code lines} list. Lines are 0-indexed; token lines are
     * 1-indexed. Returns the number of annotation changes made.
     */
    private int applyMethod(List<String> lines,
                             MethodInfo method,
                             FrameworkKind fw,
                             List<String> desiredTags,
                             String desiredDisplayName) {
        int changes = 0;

        // ── Tags ──────────────────────────────────────────────────────
        if (desiredTags != null) {
            Set<String> tagAttrNames = fw.tagAttributeNames();
            // Collect existing tag attribute line ranges (descending so deletions
            // don't invalidate earlier ranges in the same pass).
            List<int[]> tagRanges = new ArrayList<>();
            for (AttributeInfo attr : method.attributes()) {
                if (tagAttrNames.contains(attr.simpleName())) {
                    tagRanges.add(new int[]{attr.sectionStartLine(), attr.sectionStopLine()});
                }
            }

            Set<String> existing = existingTagValues(method, fw);
            Set<String> desired  = buildDesiredTagSet(desiredTags);

            if (!existing.equals(desired)) {
                // Remove existing tag attribute lines (reverse order).
                // All tag ranges are at or after method.beginLine(), so their
                // deletion does NOT shift the index of method.beginLine()-1.
                tagRanges.sort((a, b) -> Integer.compare(b[0], a[0]));
                for (int[] range : tagRanges) {
                    deleteLines(lines, range[0] - 1, range[1] - 1);
                }
                changes += existing.size();   // count all removed tags once
                // Insert new tag attributes at the method's begin-line position.
                // After the deletions above, method.beginLine()-1 (0-based) is the
                // correct insertion point regardless of how many ranges were deleted.
                int insertIdx = Math.min(method.beginLine() - 1, lines.size());
                String indent  = detectIndent(lines, insertIdx);
                for (String tag : desired) {
                    lines.add(insertIdx, indent + fw.buildTagAttribute(tag));
                    insertIdx++;
                    changes++;
                }
            }
        }

        // ── Display name (xUnit only) ─────────────────────────────────
        if (desiredDisplayName != null && fw.supportsDisplayName()) {
            changes += applyDisplayName(lines, method, desiredDisplayName);
        }

        return changes;
    }

    /**
     * Adds, replaces, or removes the {@code DisplayName} named parameter
     * in the first {@code [Fact]} or {@code [Theory]} attribute of the method.
     */
    private int applyDisplayName(List<String> lines,
                                  MethodInfo method,
                                  String desiredDisplayName) {
        for (AttributeInfo attr : method.attributes()) {
            if (!"Fact".equals(attr.simpleName()) && !"Theory".equals(attr.simpleName())) {
                continue;
            }
            int lineIdx = attr.sectionStartLine() - 1;   // 0-based
            if (lineIdx < 0 || lineIdx >= lines.size()) return 0;
            String line = lines.get(lineIdx);

            if (desiredDisplayName.isEmpty()) {
                // Remove DisplayName parameter
                String patched = removeDisplayNameParam(line, attr.simpleName());
                if (!patched.equals(line)) {
                    lines.set(lineIdx, patched);
                    return 1;
                }
            } else {
                // Set / replace DisplayName parameter
                String escaped = desiredDisplayName
                        .replace("\\", "\\\\").replace("\"", "\\\"");
                String patched = setDisplayNameParam(line, attr.simpleName(), escaped,
                                                     attr.namedArgs().containsKey("DisplayName"));
                if (!patched.equals(line)) {
                    lines.set(lineIdx, patched);
                    return 1;
                }
            }
        }
        return 0;
    }

    // ── Text helpers ──────────────────────────────────────────────────

    /** Deletes inclusive line range [from0, to0] (0-based). */
    private static void deleteLines(List<String> lines, int from0, int to0) {
        int count = to0 - from0 + 1;
        for (int i = 0; i < count && from0 < lines.size(); i++) {
            lines.remove(from0);
        }
    }

    /** Returns the leading whitespace of the line at {@code idx} (or empty). */
    private static String detectIndent(List<String> lines, int idx) {
        if (idx < 0 || idx >= lines.size()) return "        ";
        String line = lines.get(idx);
        StringBuilder sb = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == ' ' || c == '\t') sb.append(c);
            else break;
        }
        return sb.toString();
    }

    private static String removeDisplayNameParam(String line, String attrName) {
        // [Fact(DisplayName = "...")] → [Fact]
        // [Fact(DisplayName = "...", ...other...)] → [Fact(...other...)]
        return line
                .replaceAll("\\[" + attrName + "\\(\\s*DisplayName\\s*=\\s*\"[^\"]*\"\\s*\\)\\]",
                             "[" + attrName + "]")
                .replaceAll(",\\s*DisplayName\\s*=\\s*\"[^\"]*\"", "")
                .replaceAll("DisplayName\\s*=\\s*\"[^\"]*\"\\s*,\\s*", "");
    }

    private static String setDisplayNameParam(String line, String attrName,
                                               String escaped, boolean exists) {
        if (exists) {
            // Replace existing value
            return line.replaceAll("(DisplayName\\s*=\\s*)\"[^\"]*\"",
                                   "$1\"" + escaped + "\"");
        }
        // Insert as first named parameter
        if (line.contains("[" + attrName + "]")) {
            return line.replace("[" + attrName + "]",
                                "[" + attrName + "(DisplayName = \"" + escaped + "\")]");
        }
        if (line.contains("[" + attrName + "(")) {
            return line.replace("[" + attrName + "(",
                                "[" + attrName + "(DisplayName = \"" + escaped + "\", ");
        }
        return line;
    }

    private static Set<String> existingTagValues(MethodInfo method, FrameworkKind fw) {
        Set<String> tagAttrNames = fw.tagAttributeNames();
        Set<String> result = new LinkedHashSet<>();
        for (AttributeInfo attr : method.attributes()) {
            if (!tagAttrNames.contains(attr.simpleName())) continue;
            if (fw == FrameworkKind.XUNIT) {
                List<String> pos = attr.positionalArgs();
                if (pos.size() >= 2 && pos.get(1) != null) {
                    String key = pos.get(0);
                    if ("Tag".equalsIgnoreCase(key) || "Category".equalsIgnoreCase(key)) {
                        result.add(pos.get(1));
                    }
                }
            } else {
                List<String> pos = attr.positionalArgs();
                if (!pos.isEmpty() && pos.get(0) != null) {
                    result.add(pos.get(0));
                }
            }
        }
        return result;
    }

    private static Set<String> buildDesiredTagSet(List<String> desiredTags) {
        Set<String> result = new LinkedHashSet<>();
        if (desiredTags != null) {
            for (String t : desiredTags) {
                if (t != null && !t.isBlank()) result.add(t);
            }
        }
        return result;
    }

    // ── SourcePatcher.discoverMethodsByClass ──────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Parses the source file and returns a map from fully qualified class
     * name to the list of simple test-method names declared in each class.</p>
     */
    @Override
    public Map<String, List<String>> discoverMethodsByClass(
            Path sourceFile) throws IOException {
        CSharpTestParser.CompilationUnitContext tree = parse(sourceFile);
        if (tree == null) return Map.of();

        CSharpTestVisitor visitor = new CSharpTestVisitor(testMarkers);
        visitor.visit(tree);

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (MethodInfo m : visitor.getDiscoveredMethods()) {
            result.computeIfAbsent(m.fqcn(), k -> new ArrayList<>()).add(m.methodName());
        }
        return result;
    }

    // ── ANTLR4 parsing ────────────────────────────────────────────────

    private CSharpTestParser.CompilationUnitContext parse(Path file) throws IOException {
        CSharpTestLexer  lexer  = new CSharpTestLexer(CharStreams.fromPath(file));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        CSharpTestParser  parser = new CSharpTestParser(tokens);
        parser.removeErrorListeners();
        return parser.compilationUnit();
    }

    // ── Utility ───────────────────────────────────────────────────────

    private static String detectLineSeparator(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.contains("\r\n")) return "\r\n";
        if (content.contains("\r"))   return "\r";
        return "\n";
    }
}
