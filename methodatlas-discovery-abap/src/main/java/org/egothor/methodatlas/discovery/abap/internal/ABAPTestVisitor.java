package org.egothor.methodatlas.discovery.abap.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.Token;
import org.egothor.methodatlas.discovery.abap.parser.ABAPTestLexer;

/**
 * Token-stream scanner that walks a lexed ABAP file and collects ABAP Unit
 * test methods.
 *
 * <p>The scanner deliberately bypasses the structural ANTLR parse tree
 * because the ABAP source landscape contains an enormous number of
 * statement-level constructs that are hard to model in a focused grammar
 * (especially inside method bodies). Token-level scanning is robust to
 * these constructs: it only looks for a handful of well-defined patterns
 * and ignores everything else.</p>
 *
 * <h2>Detection logic</h2>
 * <ol>
 *   <li><strong>CLASS … DEFINITION … FOR TESTING.</strong> — between the
 *       {@code CLASS IDENTIFIER DEFINITION} prefix and the first
 *       {@code PERIOD}, we scan for the pair {@code FOR TESTING}. When
 *       found, the class is registered as a test class.</li>
 *   <li><strong>Method declarations.</strong> — between {@code METHODS}
 *       (optional colon) and the terminating {@code PERIOD}, identifiers
 *       are extracted; an identifier whose attribute list contains
 *       {@code FOR TESTING} (until the next comma or the terminating
 *       period) is registered as a test method.</li>
 *   <li><strong>CLASS … IMPLEMENTATION.</strong> — within an
 *       implementation block whose class name was registered, every
 *       {@code METHOD IDENTIFIER PERIOD … ENDMETHOD PERIOD} whose
 *       identifier is in the test-method set is emitted as a
 *       {@link MethodInfo} with the line numbers of its METHOD and
 *       ENDMETHOD keywords.</li>
 * </ol>
 *
 * <p>Class names are normalised to upper case so the lookup is
 * case-insensitive (ABAP is itself case-insensitive).</p>
 *
 * <p>Instances are single-use: construct one per file, call
 * {@link #scan(BufferedTokenStream)}, then read results via
 * {@link #getDiscoveredMethods()}.</p>
 *
 * <h2>Code structure</h2>
 * <p>Each phase is implemented as a separate package-private helper
 * method (header scan, body scan, single-METHODS parsing, single
 * method-impl parsing). This keeps every method's cyclomatic and
 * NPath complexity well below the project's PMD thresholds.</p>
 */
public final class ABAPTestVisitor {

    /** Map from upper-cased class name to its set of FOR TESTING method names. */
    private final Map<String, Set<String>> testMethodsByClass = new HashMap<>();

    private final List<MethodInfo> discoveredMethods = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Scans the supplied lexer token stream and populates the discovered
     * methods list.
     *
     * @param tokens fully-lexed ABAP token stream; never {@code null}
     */
    public void scan(BufferedTokenStream tokens) {
        // Tokens must be materialised before random-access is meaningful for
        // CommonTokenStream — ANTLR pulls them on demand otherwise.
        tokens.fill();

        int i = 0;
        int n = tokens.size();
        while (i < n && tokens.get(i).getType() != Token.EOF) {
            i = dispatchTopLevel(tokens, i, n);
        }
    }

    /**
     * All ABAP Unit test methods found in the file after scanning.
     *
     * @return unmodifiable list of discovered test methods
     */
    public List<MethodInfo> getDiscoveredMethods() {
        return List.copyOf(discoveredMethods);
    }

    // ─────────────────────────────────────────────────────────────────
    // Top-level dispatch
    // ─────────────────────────────────────────────────────────────────

    /**
     * If the token at {@code i} starts a {@code CLASS IDENT DEFINITION} or
     * {@code CLASS IDENT IMPLEMENTATION} block, delegates to the matching
     * handler and returns the index just past the consumed block.
     * Otherwise advances by one.
     */
    private int dispatchTopLevel(BufferedTokenStream tokens, int i, int n) {
        if (tokens.get(i).getType() != ABAPTestLexer.CLASS || i + 2 >= n) {
            return i + 1;
        }
        Token name = tokens.get(i + 1);
        Token kind = tokens.get(i + 2);
        if (name.getType() != ABAPTestLexer.IDENTIFIER) {
            return i + 1;
        }
        String className = name.getText().toUpperCase(Locale.ROOT);
        int kindType = kind.getType();
        if (kindType == ABAPTestLexer.DEFINITION) {
            return handleClassDefinition(tokens, i + 3, className);
        }
        if (kindType == ABAPTestLexer.IMPLEMENTATION) {
            return handleClassImplementation(tokens, i + 3, className);
        }
        return i + 1;
    }

    // ─────────────────────────────────────────────────────────────────
    // CLASS … DEFINITION
    // ─────────────────────────────────────────────────────────────────

    /**
     * Consumes a {@code CLASS NAME DEFINITION … ENDCLASS.} block.
     *
     * @param start      index just after {@code DEFINITION}
     * @param className  upper-cased class name
     * @return index just past {@code ENDCLASS.}
     */
    private int handleClassDefinition(BufferedTokenStream tokens, int start, String className) {
        int n = tokens.size();
        boolean classIsForTesting = headerContainsForTesting(tokens, start, n);
        int i = skipPastNextPeriod(tokens, start, n);

        Set<String> testMethods = new LinkedHashSet<>();
        i = scanDefinitionBody(tokens, i, n, testMethods);
        i = skipPastEndclass(tokens, i, n);

        if (classIsForTesting && !testMethods.isEmpty()) {
            // Merge with any existing entries (a file could in principle
            // contain repeated CLASS … DEFINITION blocks for the same name).
            testMethodsByClass
                    .computeIfAbsent(className, k -> new LinkedHashSet<>())
                    .addAll(testMethods);
        }
        return i;
    }

    /**
     * Scans from {@code start} until the first terminating PERIOD (or EOF)
     * looking for the keyword pair {@code FOR TESTING}.
     *
     * @return {@code true} if the pair was seen before the PERIOD
     */
    private static boolean headerContainsForTesting(BufferedTokenStream tokens, int start, int n) {
        for (int i = start; i < n; i++) {
            int tt = tokens.get(i).getType();
            if (tt == Token.EOF || tt == ABAPTestLexer.PERIOD) {
                return false;
            }
            if (tt == ABAPTestLexer.FOR && isFollowedBy(tokens, i, n, ABAPTestLexer.TESTING)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the class-definition body collecting names from every
     * {@code METHODS … .} statement until {@code ENDCLASS} or EOF.
     *
     * @return index of the ENDCLASS (or EOF) token
     */
    private static int scanDefinitionBody(BufferedTokenStream tokens, int start, int n,
            Set<String> testMethods) {
        int i = start;
        while (i < n) {
            int tt = tokens.get(i).getType();
            if (tt == Token.EOF || tt == ABAPTestLexer.ENDCLASS) {
                return i;
            }
            if (tt == ABAPTestLexer.METHODS) {
                i = scanMethodsBlock(tokens, i + 1, testMethods);
            } else {
                i++;
            }
        }
        return i;
    }

    /**
     * Parses a single {@code METHODS [:] decl ( , decl )* .} statement,
     * collecting names whose attribute list contains {@code FOR TESTING}.
     *
     * @param tokens token stream
     * @param start  index just after the {@code METHODS} keyword
     * @param into   accumulator receiving FOR TESTING method names
     * @return index just past the terminating PERIOD (or EOF / ENDCLASS)
     */
    private static int scanMethodsBlock(BufferedTokenStream tokens, int start, Set<String> into) {
        int n = tokens.size();
        int i = skipOptionalColon(tokens, start, n);
        // Single exit point: the loop body's last statement is a plain
        // assignment (advance past COMMA) so PMD's
        // AvoidBranchingStatementAsLastInLoop is satisfied.
        int result = i;
        while (i < n) {
            int tt = tokens.get(i).getType();
            if (isDeclListTerminator(tt)) {
                result = i;
                break;
            }
            if (tt != ABAPTestLexer.IDENTIFIER) {
                i++;
                result = i;
                continue;
            }
            String name = tokens.get(i).getText().toUpperCase(Locale.ROOT);
            int afterAttrs = scanDeclAttrs(tokens, i + 1, n, name, into);
            int sep = afterAttrs < n ? tokens.get(afterAttrs).getType() : Token.EOF;
            if (sep == ABAPTestLexer.PERIOD) {
                result = afterAttrs + 1;
                break;
            }
            if (sep != ABAPTestLexer.COMMA) {
                // ENDCLASS or EOF: stop here without advancing.
                result = afterAttrs;
                break;
            }
            // COMMA: advance past it and look for the next decl.
            i = afterAttrs + 1;
            result = i;
        }
        return result;
    }

    /**
     * Scans the attribute list of a single {@code methodDecl} until the
     * next {@code COMMA}, {@code PERIOD}, {@code ENDCLASS}, or EOF.
     * Adds {@code name} to {@code into} if the attribute list contains
     * {@code FOR TESTING}.
     *
     * @return index of the terminator token (COMMA/PERIOD/ENDCLASS/EOF)
     */
    private static int scanDeclAttrs(BufferedTokenStream tokens, int start, int n,
            String name, Set<String> into) {
        int i = start;
        boolean forTesting = false;
        while (i < n) {
            int tt = tokens.get(i).getType();
            if (isDeclAttrTerminator(tt)) {
                break;
            }
            if (tt == ABAPTestLexer.FOR && isFollowedBy(tokens, i, n, ABAPTestLexer.TESTING)) {
                forTesting = true;
                i += 2;
                continue;
            }
            i++;
        }
        if (forTesting) {
            into.add(name);
        }
        return i;
    }

    // ─────────────────────────────────────────────────────────────────
    // CLASS … IMPLEMENTATION
    // ─────────────────────────────────────────────────────────────────

    /**
     * Consumes a {@code CLASS NAME IMPLEMENTATION … ENDCLASS.} block,
     * emitting one {@link MethodInfo} for each {@code METHOD} whose name
     * is in the previously-collected FOR TESTING set for the class.
     *
     * @param start      index just after {@code IMPLEMENTATION}
     * @param className  upper-cased class name
     * @return index just past {@code ENDCLASS.}
     */
    private int handleClassImplementation(BufferedTokenStream tokens, int start, String className) {
        int n = tokens.size();
        int i = skipPastNextPeriod(tokens, start, n);

        Set<String> testMethods = testMethodsByClass.get(className);
        while (i < n) {
            int tt = tokens.get(i).getType();
            if (tt == Token.EOF || tt == ABAPTestLexer.ENDCLASS) {
                break;
            }
            if (tt == ABAPTestLexer.METHOD
                    && isFollowedBy(tokens, i, n, ABAPTestLexer.IDENTIFIER)) {
                i = processMethodImpl(tokens, i, n, testMethods, className);
            } else {
                i++;
            }
        }
        return skipPastEndclass(tokens, i, n);
    }

    /**
     * Processes a single {@code METHOD NAME. … ENDMETHOD.} block. Emits
     * a {@link MethodInfo} when {@code NAME} is in {@code testMethods}.
     *
     * @return index just past the terminating {@code ENDMETHOD.}
     */
    private int processMethodImpl(BufferedTokenStream tokens, int i, int n,
            Set<String> testMethods, String className) {
        Token methodToken = tokens.get(i);
        String methodName = tokens.get(i + 1).getText().toUpperCase(Locale.ROOT);
        int endIdx = findMethodTerminator(tokens, i + 2, n);
        int endLine = endIdx < n ? tokens.get(endIdx).getLine() : methodToken.getLine();
        if (testMethods != null && testMethods.contains(methodName)) {
            discoveredMethods.add(new MethodInfo(
                    className, methodName, methodToken.getLine(), endLine));
        }
        return skipPastEndmethod(tokens, endIdx, n);
    }

    /**
     * Returns the index of the next {@code ENDMETHOD}, {@code ENDCLASS},
     * or EOF token at or after {@code start}.
     */
    private static int findMethodTerminator(BufferedTokenStream tokens, int start, int n) {
        for (int j = start; j < n; j++) {
            int tt = tokens.get(j).getType();
            if (tt == Token.EOF || tt == ABAPTestLexer.ENDMETHOD
                    || tt == ABAPTestLexer.ENDCLASS) {
                return j;
            }
        }
        return n;
    }

    // ─────────────────────────────────────────────────────────────────
    // Token-level helpers
    // ─────────────────────────────────────────────────────────────────

    /** Whether the token at {@code i+1} (if present) has type {@code expected}. */
    private static boolean isFollowedBy(BufferedTokenStream tokens, int i, int n, int expected) {
        return i + 1 < n && tokens.get(i + 1).getType() == expected;
    }

    /** Terminator set for {@code METHODS [:] decl ( , decl )* .}. */
    private static boolean isDeclListTerminator(int tt) {
        return tt == Token.EOF || tt == ABAPTestLexer.PERIOD
                || tt == ABAPTestLexer.ENDCLASS;
    }

    /** Terminator set for an individual method-decl attribute scan. */
    private static boolean isDeclAttrTerminator(int tt) {
        return tt == Token.EOF || tt == ABAPTestLexer.COMMA
                || tt == ABAPTestLexer.PERIOD || tt == ABAPTestLexer.ENDCLASS;
    }

    /** Skips an optional leading {@code :} (used after {@code METHODS}). */
    private static int skipOptionalColon(BufferedTokenStream tokens, int i, int n) {
        if (i < n && tokens.get(i).getType() == ABAPTestLexer.COLON) {
            return i + 1;
        }
        return i;
    }

    /**
     * Advances {@code i} until a {@code PERIOD} is seen (and consumed),
     * or until EOF/ENDCLASS is hit (in which case the index points at
     * that terminator).
     */
    private static int skipPastNextPeriod(BufferedTokenStream tokens, int start, int n) {
        for (int i = start; i < n; i++) {
            int tt = tokens.get(i).getType();
            if (tt == Token.EOF || tt == ABAPTestLexer.ENDCLASS) {
                return i;
            }
            if (tt == ABAPTestLexer.PERIOD) {
                return i + 1;
            }
        }
        return n;
    }

    /** Advances past an {@code ENDCLASS} token and its trailing {@code PERIOD}. */
    private static int skipPastEndclass(BufferedTokenStream tokens, int i, int n) {
        if (i >= n || tokens.get(i).getType() != ABAPTestLexer.ENDCLASS) {
            return i;
        }
        int j = i + 1;
        if (j < n && tokens.get(j).getType() == ABAPTestLexer.PERIOD) {
            j++;
        }
        return j;
    }

    /** Advances past an {@code ENDMETHOD} token and its trailing {@code PERIOD}. */
    private static int skipPastEndmethod(BufferedTokenStream tokens, int i, int n) {
        if (i >= n || tokens.get(i).getType() != ABAPTestLexer.ENDMETHOD) {
            return i;
        }
        int j = i + 1;
        if (j < n && tokens.get(j).getType() == ABAPTestLexer.PERIOD) {
            j++;
        }
        return j;
    }
}
