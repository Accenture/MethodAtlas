package org.egothor.methodatlas.discovery.abap.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egothor.methodatlas.discovery.abap.parser.ABAPTestBaseVisitor;
import org.egothor.methodatlas.discovery.abap.parser.ABAPTestParser;

/**
 * ANTLR4 visitor that walks an ABAP parse tree produced by the
 * {@code ABAPTest} grammar and collects ABAP Unit test methods.
 *
 * <h2>Detection logic</h2>
 * <ol>
 *   <li>A class whose {@code CLASS … DEFINITION} block contains the
 *       {@code FOR TESTING} attribute is recorded as a test class.</li>
 *   <li>Within such a class, every method declaration that carries
 *       {@code FOR TESTING} is recorded as a test method name.</li>
 *   <li>In the corresponding {@code CLASS … IMPLEMENTATION} block, each
 *       {@code METHOD … ENDMETHOD} whose name appears in the collected set
 *       is emitted as a {@link MethodInfo} with its begin and end line
 *       numbers.</li>
 * </ol>
 *
 * <p>Instances are single-use: create one per source file, call
 * {@link #visit(org.antlr.v4.runtime.tree.ParseTree)}, then read results
 * via {@link #getDiscoveredMethods()}.</p>
 */
public final class ABAPTestVisitor extends ABAPTestBaseVisitor<Void> {

    /** Map from upper-cased class name to the set of FOR TESTING method names. */
    private final Map<String, Set<String>> testMethodsByClass = new HashMap<>();

    private final List<MethodInfo> discoveredMethods = new ArrayList<>();

    // ── Class definition ──────────────────────────────────────────────

    /**
     * Visits a class definition and registers FOR TESTING methods when
     * the class itself is marked FOR TESTING.
     *
     * @param ctx parse-tree context for the class definition
     * @return {@code null} (visitor protocol)
     */
    @Override
    public Void visitClassDef(ABAPTestParser.ClassDefContext ctx) {
        if (!isForTesting(ctx.classDefAttr())) {
            return null;
        }
        String className = ctx.IDENTIFIER().getText().toUpperCase(java.util.Locale.ROOT);
        Set<String> testMethods = new HashSet<>();
        for (ABAPTestParser.ClassSecContext sec : ctx.classSec()) {
            collectTestMethods(sec, testMethods);
        }
        testMethodsByClass.put(className, testMethods);
        return null;
    }

    // ── Class implementation ──────────────────────────────────────────

    /**
     * Visits a class implementation and emits {@link MethodInfo} records for
     * each method whose name is in the FOR TESTING set of its class.
     *
     * @param ctx parse-tree context for the class implementation
     * @return {@code null} (visitor protocol)
     */
    @Override
    public Void visitClassImpl(ABAPTestParser.ClassImplContext ctx) {
        String className = ctx.IDENTIFIER().getText().toUpperCase(java.util.Locale.ROOT);
        Set<String> testMethods = testMethodsByClass.get(className);
        if (testMethods == null || testMethods.isEmpty()) {
            return null;
        }
        for (ABAPTestParser.MethodImplContext m : ctx.methodImpl()) {
            String methodName = m.IDENTIFIER().getText().toUpperCase(java.util.Locale.ROOT);
            if (testMethods.contains(methodName)) {
                int beginLine = m.start.getLine();
                int endLine   = m.stop != null ? m.stop.getLine() : beginLine;
                discoveredMethods.add(new MethodInfo(className, methodName, beginLine, endLine));
            }
        }
        return null;
    }

    // ── Result accessor ───────────────────────────────────────────────

    /**
     * All ABAP Unit test methods found in the file after visiting.
     *
     * @return unmodifiable list of discovered test methods
     */
    public List<MethodInfo> getDiscoveredMethods() {
        return List.copyOf(discoveredMethods);
    }

    // ── Private helpers ───────────────────────────────────────────────

    private static boolean isForTesting(
            List<ABAPTestParser.ClassDefAttrContext> attrs) {
        for (ABAPTestParser.ClassDefAttrContext attr : attrs) {
            if (attr.FOR() != null && attr.TESTING() != null) {
                return true;
            }
        }
        return false;
    }

    private static void collectTestMethods(
            ABAPTestParser.ClassSecContext sec, Set<String> into) {
        for (ABAPTestParser.ClassMemberContext member : sec.classMember()) {
            if (member.METHODS() == null) {
                continue;
            }
            for (ABAPTestParser.MethodDeclContext decl : member.methodDecl()) {
                if (hasForTesting(decl.methodDeclAttr())) {
                    into.add(decl.IDENTIFIER().getText().toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
    }

    private static boolean hasForTesting(
            List<ABAPTestParser.MethodDeclAttrContext> attrs) {
        for (ABAPTestParser.MethodDeclAttrContext attr : attrs) {
            if (attr.FOR() != null && attr.TESTING() != null) {
                return true;
            }
        }
        return false;
    }
}
