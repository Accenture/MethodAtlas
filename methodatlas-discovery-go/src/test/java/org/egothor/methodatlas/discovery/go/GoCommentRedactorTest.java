package org.egothor.methodatlas.discovery.go;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link GoCommentRedactor}: comments blank out, string and rune
 * literals survive, every offset/line invariant holds and anything the lexer
 * cannot classify fails closed.
 */
@Tag("unit")
class GoCommentRedactorTest {

    private static final Path FILE = Path.of("auth_test.go");
    private final GoCommentRedactor redactor = new GoCommentRedactor();

    private static final String PII_SOURCE = String.join("\n",
            "// Copyright header: Jane Doe",
            "package auth_test /* owner jane.doe@acme.com */",
            "",
            "import \"testing\"",
            "",
            "/*",
            " * customer 4111-1111-1111-1111 +44 7700 900123",
            " */",
            "func TestLogin(t *testing.T) { // inline note",
            "\tu := \"http://x /* not a comment */\" /* trailing */",
            "\traw := `// also not a comment",
            "\t/* nor this */`",
            "\tr := '/'",
            "\tn := a / b",
            "\t_ = u + raw + n // end",
            "}",
            "// last line comment",
            "");

    @Test
    void supports_matchesGoSuffixOnly() {
        assertTrue(redactor.supports(Path.of("x/auth_test.go")));
        assertTrue(redactor.supports(Path.of("main.GO")));
        assertFalse(redactor.supports(Path.of("Auth.java")));
        assertFalse(redactor.supports(Path.of("Auth.cs")));
        assertFalse(redactor.supports(Path.of("go")));
        assertFalse(redactor.supports(null));
        assertFalse(redactor.supports(Path.of("/")));
        assertEquals("go", redactor.pluginId());
    }

    @Test
    void redact_blanksLineAndBlockCommentsOnly() throws CommentRedactionException {
        String out = redactor.redact(FILE, "package p // c1\nvar a = 1 /* c2 */ + 2\n");

        assertEquals("package p" + " ".repeat(6) + "\nvar a = 1" + " ".repeat(10) + "+ 2\n", out);
    }

    @Test
    void redact_keepsMarkersInsideStringsRawStringsAndRunes() throws CommentRedactionException {
        String src = "s := \"// no\"\nt := \"/* no */\"\nr := `// raw\n/* raw */`\nc := '/'\nq := \"a\\\"//b\"\n";

        assertEquals(src, redactor.redact(FILE, src));
    }

    @Test
    void redact_blanksPiiBearingCommentsCompletely() throws CommentRedactionException {
        String out = redactor.redact(FILE, PII_SOURCE);

        for (String pii : List.of("Jane Doe", "jane.doe@acme.com", "4111-1111-1111-1111", "7700 900123",
                "inline note", "trailing", "last line comment", "end")) {
            assertFalse(out.contains(pii), "must be blanked: " + pii);
        }
        assertTrue(out.contains("http://x /* not a comment */"));
        assertTrue(out.contains("// also not a comment"));
        assertTrue(out.contains("/* nor this */"));
        assertInvariants(PII_SOURCE, out);
    }

    @Test
    void redact_preservesLengthLinesAndOffsets() throws CommentRedactionException {
        String out = redactor.redact(FILE, PII_SOURCE);

        assertInvariants(PII_SOURCE, out);
        assertEquals(PII_SOURCE.indexOf("TestLogin"), out.indexOf("TestLogin"));
        assertEquals(PII_SOURCE.indexOf("raw :="), out.indexOf("raw :="));
    }

    @Test
    void redact_keepsCrlfInsideBlockComments() throws CommentRedactionException {
        String src = "a /* x\r\ny\rz */ b\r\n";

        assertEquals("a     \r\n \r     b\r\n", redactor.redact(FILE, src));
    }

    @Test
    void redact_isIdempotent() throws CommentRedactionException {
        String once = redactor.redact(FILE, PII_SOURCE);

        assertEquals(once, redactor.redact(FILE, once));
    }

    @Test
    void redact_returnsInputWhenThereIsNoComment() throws CommentRedactionException {
        assertEquals("package p\n", redactor.redact(FILE, "package p\n"));
        assertEquals("", redactor.redact(FILE, ""));
    }

    @Test
    void redact_keepsOffsetsAfterSupplementaryCharacters() throws CommentRedactionException {
        String src = "s := \"😀\" /* c */ x\n// 😀 tail\nfoo\n";

        String out = redactor.redact(FILE, src);

        assertEquals(src.length(), out.length());
        assertEquals(src.indexOf(" x\n"), out.indexOf(" x\n"));
        assertEquals(src.indexOf("foo"), out.indexOf("foo"));
        assertFalse(out.contains("tail"));
        assertTrue(out.startsWith("s := \"😀\" "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"'\\x41'", "'\\u00e9'", "'\\U0001F600'", "'\\377'", "'\\''", "'\\\\'", "'\\n'"})
    void redact_acceptsEveryRuneEscapeForm(String rune) throws CommentRedactionException {
        String src = "c := " + rune + " // note\n";

        assertEquals("c := " + rune + "        \n", redactor.redact(FILE, src));
    }

    @Test
    void redact_acceptsOperatorsTheGrammarHasNoTokenFor() throws CommentRedactionException {
        String src = "x := a / b + c - d * e % f &^ g; h <- i /* c */\n";

        assertEquals("x := a / b + c - d * e % f &^ g; h <- i" + " ".repeat(8) + "\n", redactor.redact(FILE, src));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "s := \"unterminated // secret\n",
            "s := `unterminated raw // secret\n",
            "c := 'ab // secret\n",
            "x := 1 /* open\nsecret\n"})
    void redact_failsClosedOnUnterminatedLiteralsAndComments(String src) {
        assertThrows(CommentRedactionException.class, () -> redactor.redact(FILE, src));
    }

    @Test
    void redact_isRegisteredAsService() {
        List<CommentRedactor> found = StreamSupport
                .stream(ServiceLoader.load(CommentRedactor.class).spliterator(), false)
                .filter(r -> r instanceof GoCommentRedactor).toList();

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
