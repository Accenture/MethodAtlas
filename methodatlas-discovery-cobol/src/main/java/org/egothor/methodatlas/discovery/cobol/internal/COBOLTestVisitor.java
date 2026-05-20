package org.egothor.methodatlas.discovery.cobol.internal;

import java.util.ArrayList;
import java.util.List;

import org.egothor.methodatlas.discovery.cobol.parser.COBOLTestBaseVisitor;
import org.egothor.methodatlas.discovery.cobol.parser.COBOLTestParser;

/**
 * ANTLR4 visitor that walks a COBOL parse tree produced by the
 * {@code COBOLTest} grammar and collects test paragraphs and test cases.
 *
 * <h2>Detection logic</h2>
 * <ul>
 *   <li><b>MFUnit</b> — paragraph names starting with {@code MFU-TC-} in
 *       the PROCEDURE DIVISION are emitted as test methods.</li>
 *   <li><b>COBOL-Check</b> — {@code TestCase 'name'} declarations are
 *       emitted as test methods.  The enclosing {@code TestSuite} label is
 *       recorded for context but is not itself emitted.</li>
 * </ul>
 *
 * <p>Instances are single-use: create one per source file, call
 * {@link #visit(org.antlr.v4.runtime.tree.ParseTree)}, then read results
 * via {@link #getDiscoveredMethods()} and {@link #getProgramId()}.</p>
 */
public final class COBOLTestVisitor extends COBOLTestBaseVisitor<Void> {

    private String programId = "unknown";
    private final List<MethodInfo> discoveredMethods = new ArrayList<>();

    // ── MFUnit ────────────────────────────────────────────────────────

    /**
     * Visits an MFUnit paragraph declaration and records it as a test.
     *
     * @param ctx parse-tree context for the paragraph
     * @return {@code null} (visitor protocol)
     */
    @Override
    public Void visitMfunitParagraph(COBOLTestParser.MfunitParagraphContext ctx) {
        String name = ctx.MFU_TC_ID().getText().toUpperCase(java.util.Locale.ROOT);
        int beginLine = ctx.start.getLine();
        int endLine   = ctx.stop != null ? ctx.stop.getLine() : beginLine;
        discoveredMethods.add(new MethodInfo(programId, name, beginLine, endLine));
        return null;
    }

    // ── COBOL-Check ───────────────────────────────────────────────────

    /**
     * Visits a COBOL-Check {@code TestCase} declaration and records it.
     *
     * @param ctx parse-tree context for the TestCase declaration
     * @return {@code null} (visitor protocol)
     */
    @Override
    public Void visitCobolCheckCase(COBOLTestParser.CobolCheckCaseContext ctx) {
        String name = extractString(ctx.string_());
        int beginLine = ctx.start.getLine();
        int endLine   = ctx.stop != null ? ctx.stop.getLine() : beginLine;
        discoveredMethods.add(new MethodInfo(programId, name, beginLine, endLine));
        return null;
    }

    // ── Result accessors ──────────────────────────────────────────────

    /**
     * Returns the PROGRAM-ID extracted from the source, or {@code "unknown"}.
     *
     * @return program identifier; never {@code null}
     */
    public String getProgramId() {
        return programId;
    }

    /**
     * All test methods found in the file after visiting.
     *
     * @return unmodifiable list of discovered test methods
     */
    public List<MethodInfo> getDiscoveredMethods() {
        return List.copyOf(discoveredMethods);
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Extracts the string value from a {@code string_} grammar context,
     * stripping surrounding quotes if present.
     *
     * @param ctx string context; may be {@code null}
     * @return unquoted string value; empty when {@code ctx} is {@code null}
     */
    private static String extractString(COBOLTestParser.String_Context ctx) {
        if (ctx == null) {
            return "";
        }
        String raw = ctx.getText();
        if (raw.length() >= 2
                && ((raw.startsWith("'") && raw.endsWith("'"))
                 || (raw.startsWith("\"") && raw.endsWith("\"")))) {
            return raw.substring(1, raw.length() - 1)
                      .replace("''", "'")
                      .replace("\"\"", "\"");
        }
        return raw;
    }
}
