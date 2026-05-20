package org.egothor.methodatlas.discovery.abap.internal;

import java.util.ArrayList;
import java.util.List;

import org.egothor.methodatlas.discovery.abap.parser.ECATTScriptBaseVisitor;
import org.egothor.methodatlas.discovery.abap.parser.ECATTScriptParser;

/**
 * ANTLR4 visitor that walks an ecATT parse tree produced by the
 * {@code ECATTScript} grammar and collects test function declarations.
 *
 * <p>Each {@code FUNCTION} block in an exported ecATT script file is
 * treated as one test case.  The function name is used as the method name;
 * begin and end lines span from the {@code FUNCTION} keyword to the closing
 * {@code DONE}.</p>
 *
 * <p>Instances are single-use: create one per source file, call
 * {@link #visit(org.antlr.v4.runtime.tree.ParseTree)}, then read results
 * via {@link #getDiscoveredMethods()}.</p>
 */
public final class ECATTScriptVisitor extends ECATTScriptBaseVisitor<Void> {

    private final List<MethodInfo> discoveredMethods = new ArrayList<>();

    /**
     * Visits a FUNCTION block and records it as a discovered test.
     *
     * @param ctx parse-tree context for the FUNCTION block
     * @return {@code null} (visitor protocol)
     */
    @Override
    public Void visitFunctionBlock(ECATTScriptParser.FunctionBlockContext ctx) {
        String name = ctx.IDENTIFIER().getText().toUpperCase(java.util.Locale.ROOT);
        int beginLine = ctx.start.getLine();
        int endLine   = ctx.stop != null ? ctx.stop.getLine() : beginLine;
        discoveredMethods.add(new MethodInfo("", name, beginLine, endLine));
        return null;
    }

    /**
     * All ecATT test functions found in the file after visiting.
     *
     * @return unmodifiable list of discovered test functions
     */
    public List<MethodInfo> getDiscoveredMethods() {
        return List.copyOf(discoveredMethods);
    }
}
