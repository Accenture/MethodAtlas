package org.egothor.methodatlas.discovery.cobol.internal;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.Token;
import org.egothor.methodatlas.discovery.cobol.parser.COBOLTestLexer;

/**
 * Token-stream scanner that walks a lexed COBOL file and collects MFUnit
 * paragraphs and COBOL-Check {@code TestCase} declarations.
 *
 * <p>The scanner deliberately bypasses the structural ANTLR parse tree:
 * COBOL allows an enormous number of statement-level constructs that are
 * hard to model in a focused grammar (CALL, MOVE, IF/EVALUATE, etc.).
 * Token-level scanning is robust to all of them — it only reacts to the
 * tokens that mark a test declaration and ignores everything else.</p>
 *
 * <h2>Detection logic</h2>
 * <ul>
 *   <li><strong>MFUnit</strong> — when an {@link COBOLTestLexer#MFU_TC_ID}
 *       token is seen, it is recorded as the start of a test paragraph.
 *       The paragraph end-line is the position of the next MFU paragraph
 *       header (or EOF), minus one if available.</li>
 *   <li><strong>COBOL-Check</strong> — when a {@link COBOLTestLexer#TESTCASE}
 *       token is followed by a {@code QUOTED_STRING} (or, defensively, an
 *       {@code IDENTIFIER}), the string value (quotes stripped) is recorded
 *       as the test name. The end-line is the line of the next TestCase /
 *       TestSuite header or EOF, minus one if available.</li>
 *   <li>{@code TestSuite} directives are recognised for completeness but
 *       are not emitted as test methods.</li>
 * </ul>
 *
 * <p>Instances are single-use: construct one per file, call
 * {@link #scan(BufferedTokenStream)}, then read results via
 * {@link #getDiscoveredMethods()}.</p>
 */
public final class COBOLTestVisitor {

    /**
     * Placeholder program identifier used in {@link MethodInfo#programId()}
     * because the scanner does not currently extract {@code PROGRAM-ID}
     * from the COBOL source. The {@link org.egothor.methodatlas.discovery.cobol.COBOLTestDiscovery}
     * derives the FQCN from the file-path stem regardless of this value.
     */
    public static final String UNKNOWN_PROGRAM = "unknown";

    private final List<MethodInfo> discoveredMethods = new ArrayList<>();

    /**
     * Scans the supplied lexer token stream and populates the discovered
     * methods list.
     *
     * @param tokens fully-lexed COBOL token stream; never {@code null}
     */
    public void scan(BufferedTokenStream tokens) {
        tokens.fill();
        int n = tokens.size();

        // First pass: collect the start line of every "section boundary"
        // token (MFU_TC_ID, TESTCASE, TESTSUITE, EOF). This lets us compute
        // accurate end-lines without a second linear pass per record.
        List<Integer> boundaryLines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Token t = tokens.get(i);
            int tt = t.getType();
            if (tt == COBOLTestLexer.MFU_TC_ID
                    || tt == COBOLTestLexer.TESTCASE
                    || tt == COBOLTestLexer.TESTSUITE
                    || tt == Token.EOF) {
                boundaryLines.add(t.getLine());
            }
        }

        // Second pass: emit MethodInfo records.
        for (int i = 0; i < n; i++) {
            Token t = tokens.get(i);
            int tt = t.getType();

            if (tt == COBOLTestLexer.MFU_TC_ID) {
                String name = t.getText().toUpperCase(java.util.Locale.ROOT);
                int endLine = computeEndLine(boundaryLines, t.getLine());
                discoveredMethods.add(new MethodInfo(UNKNOWN_PROGRAM, name, t.getLine(), endLine));
            } else if (tt == COBOLTestLexer.TESTCASE && i + 1 < n) {
                Token next = tokens.get(i + 1);
                String name = unquote(next.getText());
                if (!name.isEmpty()) {
                    int endLine = computeEndLine(boundaryLines, t.getLine());
                    discoveredMethods.add(new MethodInfo(UNKNOWN_PROGRAM, name, t.getLine(), endLine));
                }
            }
            // TESTSUITE is recognised as a boundary but is never emitted.
        }
    }

    /**
     * Returns the PROGRAM-ID extracted from the source, or {@code "unknown"}.
     *
     * @return program identifier; never {@code null}
     */
    public String getProgramId() {
        return UNKNOWN_PROGRAM;
    }

    /**
     * All test methods found in the file after scanning.
     *
     * @return unmodifiable list of discovered test methods
     */
    public List<MethodInfo> getDiscoveredMethods() {
        return List.copyOf(discoveredMethods);
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Finds the end line for a test record that starts at {@code startLine}.
     *
     * <p>The end line is the line just before the next section-boundary
     * token (MFUnit paragraph header, TestCase, TestSuite, or EOF). If no
     * subsequent boundary exists, {@code startLine} is returned.</p>
     *
     * @param boundaryLines pre-computed sorted list of section-boundary line
     *                      numbers
     * @param startLine     line of the current test's start token
     * @return end line for the current test (always {@code >= startLine})
     */
    private static int computeEndLine(List<Integer> boundaryLines, int startLine) {
        for (int line : boundaryLines) {
            if (line > startLine) {
                return Math.max(startLine, line - 1);
            }
        }
        return startLine;
    }

    /**
     * Strips matching single- or double-quote pairs from a raw lexer
     * token text and collapses doubled quote escapes.
     *
     * @param raw token text; may be {@code null}
     * @return unquoted string value; empty when {@code raw} is {@code null}
     *         or has no recognisable quoting
     */
    /* default */ static String unquote(String raw) {
        if (raw == null || raw.length() < 2) {
            return raw == null ? "" : raw;
        }
        char first = raw.charAt(0);
        char last  = raw.charAt(raw.length() - 1);
        if (first == '\'' && last == '\'') {
            return raw.substring(1, raw.length() - 1).replace("''", "'");
        }
        if (first == '"' && last == '"') {
            return raw.substring(1, raw.length() - 1).replace("\"\"", "\"");
        }
        return raw;
    }
}
