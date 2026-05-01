package org.egothor.methodatlas.discovery.dotnet.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egothor.methodatlas.discovery.dotnet.parser.CSharpTestBaseVisitor;
import org.egothor.methodatlas.discovery.dotnet.parser.CSharpTestParser;

/**
 * ANTLR4 visitor that walks a C# parse tree and collects structural
 * information about test methods.
 *
 * <p>Instances are single-use: create one per source file, call
 * {@link #visit(org.antlr.v4.runtime.tree.ParseTree)}, then read results via
 * {@link #getDiscoveredMethods()} and {@link #getUsingDirectives()}.</p>
 */
public final class CSharpTestVisitor extends CSharpTestBaseVisitor<Void> {

    private final Set<String> testMarkers;

    private final Deque<String> namespaceStack = new ArrayDeque<>();
    private final Deque<String> classStack     = new ArrayDeque<>();
    private final List<String>  usingDirectives = new ArrayList<>();
    private final List<MethodInfo> discoveredMethods = new ArrayList<>();

    /** Lazily resolved once the first using directive is seen. */
    private FrameworkKind framework;

    /**
     * Constructs a new visitor that uses the supplied set of test-marker
     * attribute names to identify test methods. Pass an empty set to fall back
     * to the framework-specific defaults.
     *
     * @param testMarkers attribute simple-names that mark a method as a test
     */
    public CSharpTestVisitor(Set<String> testMarkers) {
        super();
        this.testMarkers = testMarkers;
    }

    // ── Using directives ─────────────────────────────────────────────

    @Override
    public Void visitUsingDirective(CSharpTestParser.UsingDirectiveContext ctx) {
        CSharpTestParser.UsingTypeNameContext utn = ctx.usingTypeName();
        if (utn != null && utn.qualifiedName() != null) {
            usingDirectives.add(utn.qualifiedName().getText());
        }
        return visitChildren(ctx);
    }

    // ── Namespace tracking ────────────────────────────────────────────

    @Override
    public Void visitFileScopedNamespaceDeclaration(
            CSharpTestParser.FileScopedNamespaceDeclarationContext ctx) {
        String name = ctx.qualifiedName().getText();
        namespaceStack.push(name);
        visitChildren(ctx);
        namespaceStack.pop();
        return null;
    }

    @Override
    public Void visitNamespaceDeclaration(
            CSharpTestParser.NamespaceDeclarationContext ctx) {
        String name = ctx.qualifiedName().getText();
        namespaceStack.push(name);
        visitChildren(ctx);
        namespaceStack.pop();
        return null;
    }

    // ── Type tracking ─────────────────────────────────────────────────

    @Override
    public Void visitTypeDeclaration(
            CSharpTestParser.TypeDeclarationContext ctx) {
        String name = ctx.identifier().getText();
        classStack.push(name);
        visitChildren(ctx);
        classStack.pop();
        return null;
    }

    // ── Method collection ─────────────────────────────────────────────

    @Override
    public Void visitMethodDeclaration(
            CSharpTestParser.MethodDeclarationContext ctx) {
        // Resolve framework lazily (all using directives are visited before methods
        // because the grammar rule order is compilationUnit → usingDirective* → members)
        if (framework == null) {
            framework = FrameworkKind.detect(usingDirectives);
        }

        List<AttributeInfo> attrs = collectAttributes(ctx.attributeSection());
        if (!isTestMethod(attrs)) {
            // Do not recurse into method body — it is already opaque in the grammar.
            return null;
        }

        String methodName = ctx.memberName().getText();
        // Strip any explicit interface prefix: IFoo.Method → Method
        int dotIdx = methodName.lastIndexOf('.');
        if (dotIdx >= 0) {
            methodName = methodName.substring(dotIdx + 1);
        }

        int beginLine = ctx.start.getLine();
        int endLine   = ctx.stop  != null ? ctx.stop.getLine() : beginLine;

        discoveredMethods.add(new MethodInfo(buildFqcn(), methodName, attrs, beginLine, endLine));
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String buildFqcn() {
        StringBuilder sb = new StringBuilder();
        // namespaceStack is LIFO; bottom = outermost namespace
        List<String> ns = new ArrayList<>(namespaceStack);
        Collections.reverse(ns);
        for (String part : ns) {
            if (!sb.isEmpty()) { sb.append('.'); }
            sb.append(part);
        }
        // classStack is LIFO; bottom = outermost class
        List<String> cls = new ArrayList<>(classStack);
        Collections.reverse(cls);
        for (String part : cls) {
            if (!sb.isEmpty()) { sb.append('.'); }
            sb.append(part);
        }
        return sb.toString();
    }

    private boolean isTestMethod(List<AttributeInfo> attrs) {
        Set<String> markers = testMarkers.isEmpty()
                ? resolvedFramework().defaultTestMarkers()
                : testMarkers;
        return attrs.stream().anyMatch(a -> markers.contains(a.simpleName()));
    }

    private FrameworkKind resolvedFramework() {
        if (framework == null) {
            framework = FrameworkKind.detect(usingDirectives);
        }
        return framework;
    }

    private List<AttributeInfo> collectAttributes(
            List<CSharpTestParser.AttributeSectionContext> sections) {
        List<AttributeInfo> result = new ArrayList<>();
        for (CSharpTestParser.AttributeSectionContext sec : sections) {
            int secStart = sec.start.getLine();
            int secStop  = sec.stop  != null ? sec.stop.getLine() : secStart;
            for (CSharpTestParser.AttributeContext attrCtx : sec.attributeList().attribute()) {
                result.add(parseAttribute(attrCtx, secStart, secStop));
            }
        }
        return result;
    }

    private AttributeInfo parseAttribute(CSharpTestParser.AttributeContext ctx,
                                          int secStart, int secStop) {
        String qualName = ctx.qualifiedName().getText();
        // simple name = last dot-segment
        int dot = qualName.lastIndexOf('.');
        String simpleName = dot >= 0 ? qualName.substring(dot + 1) : qualName;

        List<String> positional = new ArrayList<>();
        Map<String, String> named = new LinkedHashMap<>();

        if (ctx.attributeArgs() != null) {
            for (CSharpTestParser.AttributeArgContext arg : ctx.attributeArgs().attributeArg()) {
                collectAttributeArg(arg, positional, named);
            }
        }
        // List.copyOf rejects null; positional args that aren't string literals are stored as null
        return new AttributeInfo(simpleName, Collections.unmodifiableList(new ArrayList<>(positional)),
                Map.copyOf(named), secStart, secStop);
    }

    /**
     * Adds a single attribute argument to the appropriate collection:
     * named arguments go into {@code named}; positional arguments go into
     * {@code positional}.
     */
    private static void collectAttributeArg(CSharpTestParser.AttributeArgContext arg,
                                             List<String> positional,
                                             Map<String, String> named) {
        if (arg.identifier() != null && arg.EQ() != null) {
            // named argument
            String val = extractString(arg.attributeValue());
            if (val != null) {
                named.put(arg.identifier().getText(), val);
            }
        } else {
            // positional argument
            positional.add(extractString(arg.attributeValue()));
        }
    }

    private static String extractString(CSharpTestParser.AttributeValueContext ctx) {
        if (ctx == null) { return null; }
        CSharpTestParser.StringLiteralContext sl = ctx.stringLiteral();
        if (sl == null) { return null; }
        return unquote(sl.getText());
    }

    /* default */ static String unquote(String raw) {
        if (raw == null) { return null; }
        if (raw.startsWith("\"\"\"")) {
            // raw string — strip delimiters
            int end = raw.lastIndexOf("\"\"\"");
            return end > 2 ? raw.substring(3, end) : "";
        }
        if (raw.startsWith("@\"") && raw.endsWith("\"")) {
            return raw.substring(2, raw.length() - 1).replace("\"\"", "\"");
        }
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            String inner = raw.substring(1, raw.length() - 1);
            return inner
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n",  "\n")
                    .replace("\\r",  "\r")
                    .replace("\\t",  "\t");
        }
        return raw;
    }

    // ── Result accessors ──────────────────────────────────────────────

    /** All test methods found in the file after visiting. */
    public List<MethodInfo> getDiscoveredMethods() {
        return List.copyOf(discoveredMethods);
    }

    /** Using-directive namespace strings, in declaration order. */
    public List<String> getUsingDirectives() {
        return List.copyOf(usingDirectives);
    }

    /** Framework detected from using directives; {@code null} before visiting. */
    public FrameworkKind getFramework() {
        return resolvedFramework();
    }
}
