package org.egothor.methodatlas.discovery.go.internal;

import java.util.ArrayList;
import java.util.List;

import org.egothor.methodatlas.discovery.go.parser.GoTestBaseVisitor;
import org.egothor.methodatlas.discovery.go.parser.GoTestParser;

/**
 * ANTLR4 visitor that walks a Go parse tree produced by the {@code GoTest}
 * grammar and collects structural information about test functions.
 *
 * <p>Instances are single-use: create one per source file, call
 * {@link #visit(org.antlr.v4.runtime.tree.ParseTree)}, then read results via
 * {@link #getDiscoveredMethods()} and {@link #getPackageName()}.</p>
 *
 * <h2>Test detection</h2>
 * <p>A top-level function is identified as a test if:</p>
 * <ol>
 *   <li>Its name starts with {@code "Test"}.</li>
 *   <li>The character immediately after {@code "Test"} (if any) is upper-case
 *       or an underscore — per {@code go help testfunc}.</li>
 *   <li>Its parameter list contains a parameter whose type is
 *       {@code *testing.T}.</li>
 * </ol>
 * <p>Methods (functions with a receiver) and non-test top-level functions are
 * silently ignored.</p>
 */
public final class GoTestVisitor extends GoTestBaseVisitor<Void> {

    private static final int TEST_PREFIX_LENGTH = "Test".length();

    private String packageName = "unknown";
    private final List<MethodInfo> discoveredMethods = new ArrayList<>();

    /** {@inheritDoc} */
    @Override
    public Void visitPackageDecl(GoTestParser.PackageDeclContext ctx) {
        packageName = ctx.IDENTIFIER().getText();
        return null;
    }

    /**
     * Visits a top-level function declaration and records it when it satisfies
     * the Go test-function convention.
     *
     * @param ctx parse-tree context for the function declaration
     * @return {@code null} (visitor protocol)
     */
    @Override
    public Void visitFuncDecl(GoTestParser.FuncDeclContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        if (!isTestFunctionName(name)) {
            return null;
        }
        if (!hasTestingTParameter(ctx.parameters())) {
            return null;
        }
        int beginLine = ctx.start.getLine();
        int endLine   = ctx.stop != null ? ctx.stop.getLine() : beginLine;
        discoveredMethods.add(new MethodInfo(name, beginLine, endLine));
        return null;
    }

    // visitMethodDecl intentionally not overridden: Go test functions are
    // always top-level funcs, never methods on a type.

    // ── Result accessors ──────────────────────────────────────────────

    /**
     * Package name extracted from the {@code package} declaration, or
     * {@code "unknown"} when no declaration was found.
     *
     * @return package name; never {@code null}
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * All test functions found in the file after visiting.
     *
     * @return unmodifiable list of discovered test methods
     */
    public List<MethodInfo> getDiscoveredMethods() {
        return List.copyOf(discoveredMethods);
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code name} follows the Go test-function
     * naming convention: starts with {@code "Test"}, and the next character
     * (if present) is upper-case or {@code '_'}.
     *
     * @param name function name to check
     * @return {@code true} for valid test-function names
     */
    private static boolean isTestFunctionName(String name) {
        if (!name.startsWith("Test")) {
            return false;
        }
        if (name.length() == TEST_PREFIX_LENGTH) {
            return true; // bare "Test" is valid per go test spec
        }
        char next = name.charAt(TEST_PREFIX_LENGTH);
        return Character.isUpperCase(next) || next == '_';
    }

    /**
     * Returns {@code true} when the parameter list contains a parameter of
     * type {@code *testing.T}.
     *
     * @param ctx parameter-list context from a function declaration
     * @return {@code true} if {@code *testing.T} is present
     */
    private static boolean hasTestingTParameter(GoTestParser.ParametersContext ctx) {
        if (ctx == null) {
            return false;
        }
        GoTestParser.ParamListContext pl = ctx.paramList();
        if (pl == null) {
            return false;
        }
        for (GoTestParser.ParamDeclContext pd : pl.paramDecl()) {
            if (isPointerToTestingT(pd.type_())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code t} represents the type {@code *testing.T}.
     *
     * <p>The check inspects the ANTLR4 parse tree structurally:</p>
     * <ol>
     *   <li>The type_ must have a STAR token (pointer operator).</li>
     *   <li>The inner type_ must be a {@code typeName} whose text equals
     *       {@code "testing.T"}.</li>
     * </ol>
     *
     * @param t type context from a parameter declaration
     * @return {@code true} for {@code *testing.T}
     */
    private static boolean isPointerToTestingT(GoTestParser.Type_Context t) {
        if (t == null) {
            return false;
        }
        // *testing.T → STAR type_ where the inner type_ is typeName "testing.T"
        if (t.STAR() == null) {
            return false;
        }
        List<GoTestParser.Type_Context> innerTypes = t.type_();
        if (innerTypes.isEmpty()) {
            return false;
        }
        GoTestParser.TypeNameContext tn = innerTypes.get(0).typeName();
        return tn != null && "testing.T".equals(tn.getText());
    }
}
