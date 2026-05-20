package org.egothor.methodatlas.discovery.abap.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
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
 * {@link #scan(TokenStream)}, then read results via
 * {@link #getDiscoveredMethods()}.</p>
 */
public final class ABAPTestVisitor {

    /** Map from upper-cased class name to its set of FOR TESTING method names. */
    private final Map<String, Set<String>> testMethodsByClass = new HashMap<>();

    private final List<MethodInfo> discoveredMethods = new ArrayList<>();

    /**
     * Scans the supplied lexer token stream and populates the discovered
     * methods list.
     *
     * @param tokens fully-lexed ABAP token stream; never {@code null}
     */
    public void scan(TokenStream tokens) {
        // Tokens must be materialised before random-access is meaningful for
        // CommonTokenStream — ANTLR pulls them on demand otherwise.
        tokens.fill();

        int i = 0;
        int n = tokens.size();
        while (i < n) {
            Token t = tokens.get(i);
            if (t.getType() == Token.EOF) {
                break;
            }
            if (t.getType() == ABAPTestLexer.CLASS && i + 2 < n) {
                Token name = tokens.get(i + 1);
                Token kind = tokens.get(i + 2);
                if (name.getType() == ABAPTestLexer.IDENTIFIER) {
                    if (kind.getType() == ABAPTestLexer.DEFINITION) {
                        i = handleClassDefinition(tokens, i + 3,
                                name.getText().toUpperCase(Locale.ROOT));
                        continue;
                    }
                    if (kind.getType() == ABAPTestLexer.IMPLEMENTATION) {
                        i = handleClassImplementation(tokens, i + 3,
                                name.getText().toUpperCase(Locale.ROOT));
                        continue;
                    }
                }
            }
            i++;
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

    // ── CLASS DEFINITION block ────────────────────────────────────────

    private int handleClassDefinition(TokenStream tokens, int start, String className) {
        int n = tokens.size();

        // Phase A: scan the header (from `DEFINITION` until the first PERIOD)
        // and remember whether the class itself is FOR TESTING.
        int i = start;
        boolean classIsForTesting = false;
        while (i < n) {
            Token t = tokens.get(i);
            if (t.getType() == Token.EOF || t.getType() == ABAPTestLexer.PERIOD) {
                break;
            }
            if (t.getType() == ABAPTestLexer.FOR && i + 1 < n
                    && tokens.get(i + 1).getType() == ABAPTestLexer.TESTING) {
                classIsForTesting = true;
                i += 2;
                continue;
            }
            i++;
        }
        if (i < n && tokens.get(i).getType() == ABAPTestLexer.PERIOD) {
            i++; // consume the header-terminating PERIOD
        }

        // Phase B: scan the body (until ENDCLASS) for method declarations.
        Set<String> testMethods = new LinkedHashSet<>();
        while (i < n) {
            Token t = tokens.get(i);
            int tt = t.getType();
            if (tt == Token.EOF || tt == ABAPTestLexer.ENDCLASS) {
                break;
            }
            if (tt == ABAPTestLexer.METHODS) {
                i = scanMethodsBlock(tokens, i + 1, testMethods);
                continue;
            }
            i++;
        }
        // Consume ENDCLASS PERIOD if we landed on ENDCLASS.
        if (i < n && tokens.get(i).getType() == ABAPTestLexer.ENDCLASS) {
            i++;
            if (i < n && tokens.get(i).getType() == ABAPTestLexer.PERIOD) {
                i++;
            }
        }

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
     * Parses a single {@code METHODS [:] decl ( , decl )* .} statement,
     * collecting names whose attribute list contains {@code FOR TESTING}.
     *
     * @param tokens token stream
     * @param start  index just after the {@code METHODS} keyword
     * @param into   accumulator receiving FOR TESTING method names
     * @return index just past the terminating PERIOD (or EOF / ENDCLASS)
     */
    private static int scanMethodsBlock(TokenStream tokens, int start, Set<String> into) {
        int n = tokens.size();
        int i = start;
        // Optional leading colon.
        if (i < n && tokens.get(i).getType() == ABAPTestLexer.COLON) {
            i++;
        }
        // Iterate decls separated by comma, terminated by period.
        while (i < n) {
            // Find the next IDENTIFIER (method name) at this position, skipping
            // unexpected leading tokens defensively.
            Token first = tokens.get(i);
            int ft = first.getType();
            if (ft == Token.EOF || ft == ABAPTestLexer.PERIOD
                    || ft == ABAPTestLexer.ENDCLASS) {
                break;
            }
            if (ft != ABAPTestLexer.IDENTIFIER) {
                i++;
                continue;
            }
            String name = first.getText().toUpperCase(Locale.ROOT);
            i++;

            // Scan attributes until COMMA or PERIOD, looking for FOR TESTING.
            boolean forTesting = false;
            while (i < n) {
                Token t = tokens.get(i);
                int tt = t.getType();
                if (tt == Token.EOF || tt == ABAPTestLexer.COMMA
                        || tt == ABAPTestLexer.PERIOD || tt == ABAPTestLexer.ENDCLASS) {
                    break;
                }
                if (tt == ABAPTestLexer.FOR && i + 1 < n
                        && tokens.get(i + 1).getType() == ABAPTestLexer.TESTING) {
                    forTesting = true;
                    i += 2;
                    continue;
                }
                i++;
            }
            if (forTesting) {
                into.add(name);
            }
            // Consume separator.
            if (i < n) {
                int tt = tokens.get(i).getType();
                if (tt == ABAPTestLexer.COMMA) {
                    i++;
                    continue;
                }
                if (tt == ABAPTestLexer.PERIOD) {
                    i++;
                    break;
                }
                if (tt == ABAPTestLexer.ENDCLASS || tt == Token.EOF) {
                    break;
                }
            }
        }
        return i;
    }

    // ── CLASS IMPLEMENTATION block ────────────────────────────────────

    private int handleClassImplementation(TokenStream tokens, int start, String className) {
        int n = tokens.size();
        int i = start;

        // Consume the header-terminating PERIOD.
        while (i < n) {
            int tt = tokens.get(i).getType();
            if (tt == ABAPTestLexer.PERIOD) {
                i++;
                break;
            }
            if (tt == Token.EOF || tt == ABAPTestLexer.ENDCLASS) {
                return i;
            }
            i++;
        }

        Set<String> testMethods = testMethodsByClass.get(className);
        while (i < n) {
            Token t = tokens.get(i);
            int tt = t.getType();
            if (tt == Token.EOF || tt == ABAPTestLexer.ENDCLASS) {
                break;
            }
            if (tt == ABAPTestLexer.METHOD && i + 1 < n
                    && tokens.get(i + 1).getType() == ABAPTestLexer.IDENTIFIER) {
                Token nameTok = tokens.get(i + 1);
                String methodName = nameTok.getText().toUpperCase(Locale.ROOT);
                int methodStartLine = t.getLine();
                int j = i + 2;
                // Find ENDMETHOD that terminates this method.
                while (j < n) {
                    int jt = tokens.get(j).getType();
                    if (jt == Token.EOF || jt == ABAPTestLexer.ENDMETHOD
                            || jt == ABAPTestLexer.ENDCLASS) {
                        break;
                    }
                    j++;
                }
                int endLine = j < n ? tokens.get(j).getLine() : methodStartLine;
                if (testMethods != null && testMethods.contains(methodName)) {
                    discoveredMethods.add(new MethodInfo(
                            className, methodName, methodStartLine, endLine));
                }
                // Advance past ENDMETHOD (and its trailing PERIOD, if any).
                if (j < n && tokens.get(j).getType() == ABAPTestLexer.ENDMETHOD) {
                    j++;
                    if (j < n && tokens.get(j).getType() == ABAPTestLexer.PERIOD) {
                        j++;
                    }
                }
                i = j;
                continue;
            }
            i++;
        }
        // Consume ENDCLASS PERIOD if present.
        if (i < n && tokens.get(i).getType() == ABAPTestLexer.ENDCLASS) {
            i++;
            if (i < n && tokens.get(i).getType() == ABAPTestLexer.PERIOD) {
                i++;
            }
        }
        return i;
    }
}
