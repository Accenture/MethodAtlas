package org.egothor.methodatlas.discovery.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link TypeScriptCommentRedactor}. The redaction tests drive the
 * real Node.js worker and are skipped when Node.js 18+ is absent; the
 * availability tests use an injected environment and always run.
 */
@Tag("unit")
class TypeScriptCommentRedactorTest {

    /** Held strongly so handlers attached in a test are not lost to logger garbage collection. */
    private static final java.util.logging.Logger REDACTOR_LOG =
            java.util.logging.Logger.getLogger(TypeScriptCommentRedactor.class.getName());

    private static final Path TS = Path.of("auth.test.ts");
    private static final TypeScriptCommentRedactor redactor = new TypeScriptCommentRedactor();
    /** Detected once; the redaction tests are skipped when Node.js is absent. */
    private static final boolean nodeUsable = redactor.supports(TS);

    private static void requireNode() {
        Assumptions.assumeTrue(nodeUsable, "Node.js 18+ is required");
    }

    private static final String PII_SOURCE = String.join("\n",
            "#!/usr/bin/env node // shebang secret",
            "// Copyright header: Jane Doe",
            "import { it } from 'vitest'; /* owner jane.doe@acme.com */",
            "",
            "/**",
            " * JSDoc customer 4111-1111-1111-1111 +44 7700 900123",
            " */",
            "it('logs in', () => { // inline note",
            "  const u = 'http://x /* not a comment */'; /* trailing */",
            "  const t = `// tpl ${ 1 /* inner secret */ } /* tpl2 */ ${'//str'}`;",
            "  const r = /[/*]/g; const d = a / b;",
            "  expect(u + t + r + d).toBe(1); // end",
            "});",
            "// last line comment",
            "");

    @Test
    void supports_matchesTypeScriptAndJavaScriptSuffixesOnly() {
        requireNode();
        for (String name : List.of("a.ts", "a.tsx", "a.mts", "a.cts", "a.js", "a.jsx", "a.mjs", "a.cjs",
                "x/a.test.ts", "A.TS", "a.spec.js")) {
            assertTrue(redactor.supports(Path.of(name)), name);
        }
        for (String name : List.of("A.java", "a.py", "a.cs", "a.go", "ts", "a.json", "a.txt")) {
            assertFalse(redactor.supports(Path.of(name)), name);
        }
        assertFalse(redactor.supports(null));
        assertFalse(redactor.supports(Path.of("").toAbsolutePath().getRoot()));
        assertEquals("typescript", redactor.pluginId());
    }

    @Test
    void redact_blanksLineBlockAndJsdocComments() throws CommentRedactionException {
        requireNode();
        String out = redactor.redact(TS, "const a = 1; // c1\n/** doc */ let b = /* c2 */ 2;\n");

        assertEquals("const a = 1;" + " ".repeat(6) + "\n" + " ".repeat(10) + " let b = " + " ".repeat(8) + " 2;\n",
                out);
    }

    @Test
    void redact_keepsMarkersInsideStringsTemplatesAndRegex() throws CommentRedactionException {
        requireNode();
        String src = "const s = '// no';\nconst t = \"/* no */\";\nconst u = `// a ${ '/* b */' } /* c */`;\n"
                + "const r = /[/*]/g;\nconst d = a / b / c;\n";

        assertEquals(src, redactor.redact(TS, src));
    }

    @Test
    void redact_blanksCommentInsideTemplateSubstitution() throws CommentRedactionException {
        requireNode();
        String src = "const u = `x ${ 1 /* secret */ } y`;\n";

        String out = redactor.redact(TS, src);

        assertFalse(out.contains("secret"));
        assertTrue(out.contains("`x ${ 1 "));
        assertTrue(out.contains(" } y`;"));
    }

    @Test
    void redact_blanksPiiBearingCommentsCompletely() throws CommentRedactionException {
        requireNode();
        String out = redactor.redact(TS, PII_SOURCE);

        for (String pii : List.of("shebang secret", "Jane Doe", "jane.doe@acme.com", "4111-1111-1111-1111",
                "7700 900123", "inline note", "trailing", "inner secret", "last line comment", "JSDoc")) {
            assertFalse(out.contains(pii), "must be blanked: " + pii);
        }
        assertTrue(out.contains("'http://x /* not a comment */'"));
        assertTrue(out.contains("// tpl "));
        assertTrue(out.contains("/* tpl2 */"));
        assertTrue(out.contains("'//str'"));
        assertTrue(out.contains("/[/*]/g"));
        assertInvariants(PII_SOURCE, out);
    }

    @Test
    void redact_preservesLengthLinesAndOffsets() throws CommentRedactionException {
        requireNode();
        String out = redactor.redact(TS, PII_SOURCE);

        assertInvariants(PII_SOURCE, out);
        assertEquals(PII_SOURCE.indexOf("logs in"), out.indexOf("logs in"));
        assertEquals(PII_SOURCE.indexOf("expect("), out.indexOf("expect("));
    }

    @Test
    void redact_keepsCrlfAndOffsetsAfterSupplementaryCharacters() throws CommentRedactionException {
        requireNode();
        String src = "const s = '😀'; /* c\r\nd */ let x;\r\n// 😀 tail\r\nlet foo;\r\n";

        String out = redactor.redact(TS, src);

        assertInvariants(src, out);
        assertEquals(src.indexOf("let x"), out.indexOf("let x"));
        assertEquals(src.indexOf("foo"), out.indexOf("foo"));
        assertFalse(out.contains("tail"));
    }

    @Test
    void redact_isIdempotent() throws CommentRedactionException {
        requireNode();
        String once = redactor.redact(TS, PII_SOURCE);

        assertEquals(once, redactor.redact(TS, once));
    }

    @Test
    void redact_returnsInputWhenThereIsNoComment() throws CommentRedactionException {
        requireNode();
        assertEquals("let a = 1;\n", redactor.redact(TS, "let a = 1;\n"));
        assertEquals("", redactor.redact(TS, ""));
    }

    @Test
    void redact_blanksHashbangOnly() throws CommentRedactionException {
        requireNode();
        assertEquals("                \nlet a = 1;\n",
                redactor.redact(Path.of("run.js"), "#!/usr/bin/env a\nlet a = 1;\n"));
        assertEquals("                ", redactor.redact(Path.of("run.js"), "#!/usr/bin/env a"));
    }

    @Test
    void redact_parsesJsxInTsxAndJsxFiles() throws CommentRedactionException {
        requireNode();
        String src = "const x = <div title=\"// no\">{/* c */}// text\n</div>; // end\n";

        for (String name : List.of("a.tsx", "a.jsx")) {
            String out = redactor.redact(Path.of(name), src);
            assertTrue(out.contains("title=\"// no\""), name);
            assertTrue(out.contains("// text"), name);
            assertFalse(out.contains("end"), name);
            assertFalse(out.contains("/* c */"), name);
            assertInvariants(src, out);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "const s = 'unterminated // secret\n",
            "const t = `unterminated // secret\n",
            "let a = 1; /* open\nsecret\n",
            "function ( {\n// secret\n"})
    void redact_failsClosedOnSyntacticallyBrokenSource(String src) {
        requireNode();
        assertThrows(CommentRedactionException.class, () -> redactor.redact(TS, src));
    }

    @Test
    void redact_neverLeaksCommentsAroundALoneSurrogate() {
        requireNode();
        String src = "const s = '\ud800'; // secret\n";

        try {
            String out = redactor.redact(TS, src);
            assertFalse(out.contains("secret"));
            assertEquals(src.length(), out.length());
        } catch (CommentRedactionException expected) {
            // fail-closed is the other acceptable outcome
            assertTrue(expected.getMessage() != null);
        }
    }

    @Test
    void redact_survivesAFailureAndKeepsServing() throws CommentRedactionException {
        requireNode();
        assertThrows(CommentRedactionException.class, () -> redactor.redact(TS, "let = ;;; ((\n"));

        assertEquals("let a = 1;        \n", redactor.redact(TS, "let a = 1; // zzzz\n"));
    }

    @Test
    void supports_isFalseWhenNodeIsUnavailable() {
        NodeEnvironment absent = mock(NodeEnvironment.class);
        when(absent.isAvailable()).thenReturn(false);
        AtomicInteger detections = new AtomicInteger();
        TypeScriptCommentRedactor noNode = new TypeScriptCommentRedactor(() -> {
            detections.incrementAndGet();
            return absent;
        });

        assertFalse(noNode.supports(Path.of("a.ts")));
        assertFalse(noNode.supports(Path.of("b.js")));
        assertEquals(1, detections.get(), "Node.js is detected once");
    }

    @Test
    void supports_warnsNamingTheMissingRuntime() {
        NodeEnvironment absent = mock(NodeEnvironment.class);
        when(absent.isAvailable()).thenReturn(false);
        List<String> warnings = new java.util.ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        REDACTOR_LOG.addHandler(handler);
        try {
            assertFalse(new TypeScriptCommentRedactor(() -> absent).supports(Path.of("a.ts")));
        } finally {
            REDACTOR_LOG.removeHandler(handler);
        }

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("Node.js"), warnings.get(0));
    }

    @Test
    void redact_failsClosedWhenNodeIsUnavailable() {
        NodeEnvironment absent = mock(NodeEnvironment.class);
        when(absent.isAvailable()).thenReturn(false);
        TypeScriptCommentRedactor noNode = new TypeScriptCommentRedactor(() -> absent);

        assertThrows(CommentRedactionException.class, () -> noNode.redact(TS, "let a; // secret\n"));
    }

    @Test
    void supports_doesNotDetectNodeForOtherLanguages() {
        AtomicInteger detections = new AtomicInteger();
        TypeScriptCommentRedactor lazy = new TypeScriptCommentRedactor(() -> {
            detections.incrementAndGet();
            return mock(NodeEnvironment.class);
        });

        assertFalse(lazy.supports(Path.of("A.java")));
        assertFalse(lazy.supports(Path.of("a.py")));
        assertEquals(0, detections.get());
    }

    @Test
    void redactor_isRegisteredAsService() {
        List<CommentRedactor> found = StreamSupport
                .stream(ServiceLoader.load(CommentRedactor.class).spliterator(), false)
                .filter(r -> r instanceof TypeScriptCommentRedactor).toList();

        assertEquals(1, found.size());
    }

    private static void assertInvariants(String src, String out) {
        assertEquals(src.length(), out.length());
        for (int i = 0; i < src.length(); i++) {
            char s = src.charAt(i);
            if (s == '\n' || s == '\r') {
                assertEquals(s, out.charAt(i), "line terminator moved at " + i);
            } else {
                assertTrue(out.charAt(i) == s || out.charAt(i) == ' ', "unexpected change at " + i);
            }
        }
    }
}
