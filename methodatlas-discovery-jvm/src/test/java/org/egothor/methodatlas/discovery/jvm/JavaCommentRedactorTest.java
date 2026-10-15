package org.egothor.methodatlas.discovery.jvm;

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
 * Tests for {@link JavaCommentRedactor}: comments blank out, string literals
 * survive, and every offset/line invariant the write-back path relies on holds.
 */
@Tag("unit")
class JavaCommentRedactorTest {

    private static final Path FILE = Path.of("LoginTest.java");

    private static final String PII_SOURCE = """
            /*
             * Copyright header: Jane Doe
             */
            package com.example;

            import org.junit.jupiter.api.Test;

            /**
             * Login tests.
             * @author jane.doe@acme.com, +44 7700 900123
             */
            public class LoginTest {

                // customer 4111-1111-1111-1111 reported this
                @Test
                void testLogin() { // inline note
                    String url = "http://x /* not a comment */"; /* trailing */
                    String block = \"""
                        // also not a comment
                        \""";
                    /** javadoc on a local-ish orphan */
                }

                /* block before method */
                @Test
                void testLogout() { }
                // orphan comment at end of class
            }
            // trailing file comment
            """;

    private final JavaCommentRedactor redactor = new JavaCommentRedactor();

    private String redact(String source) throws CommentRedactionException {
        return redactor.redact(FILE, source);
    }

    @Test
    void javadocBlockAndLineCommentsBecomeSpaces() throws Exception {
        String out = redact(PII_SOURCE);

        for (String gone : List.of("Copyright header", "Login tests", "@author", "customer", "inline note",
                "trailing */", "javadoc on a local-ish", "block before method", "orphan comment",
                "trailing file comment")) {
            assertFalse(out.contains(gone), "comment text survived: " + gone);
        }
        assertTrue(out.contains("void testLogin()"));
        assertTrue(out.contains("void testLogout()"));
        assertTrue(out.contains("@Test"));
    }

    @Test
    void commentMarkersInsideStringLiteralsAndTextBlocksAreKept() throws Exception {
        String out = redact(PII_SOURCE);

        assertTrue(out.contains("\"http://x /* not a comment */\""), out);
        assertTrue(out.contains("// also not a comment"), "text block content must stay");
    }

    @Test
    void piiInJavadocIsFullyBlanked() throws Exception {
        String source = """
                /**
                 * @author jane.doe@acme.com, +44 7700 900123
                 */
                class A { }
                """;

        String out = redact(source);

        assertFalse(out.contains("jane"));
        assertFalse(out.contains("acme"));
        assertFalse(out.contains("7700"));
        assertEquals("", out.replace(" ", "").replace("\n", "").replace("classA{}", ""),
                "only the code may remain");
    }

    @Test
    void lengthLinesAndIdentifierOffsetsAreUnchanged() throws Exception {
        String out = redact(PII_SOURCE);

        assertEquals(PII_SOURCE.length(), out.length());
        assertEquals(count(PII_SOURCE, '\n'), count(out, '\n'));
        assertEquals(PII_SOURCE.lines().count(), out.lines().count());
        for (String id : List.of("testLogin", "testLogout", "LoginTest {", "package com.example;")) {
            assertEquals(PII_SOURCE.indexOf(id), out.indexOf(id), "offset of " + id);
        }
        for (int i = 0; i < PII_SOURCE.length(); i++) {
            if (PII_SOURCE.charAt(i) == '\n') {
                assertEquals('\n', out.charAt(i), "newline moved at " + i);
            }
        }
    }

    @Test
    void redactionIsIdempotent() throws Exception {
        String once = redact(PII_SOURCE);

        assertEquals(once, redact(once));
    }

    @Test
    void sourceWithoutCommentsIsReturnedEqual() throws Exception {
        String source = "class A { String s = \"//x\"; }\n";

        assertEquals(source, redact(source));
    }

    @ParameterizedTest
    @ValueSource(strings = { "\r\n", "\n", "\r" })
    void lineTerminatorStylesKeepTheirTerminators(String eol) throws Exception {
        String source = "// first" + eol + "class A {" + eol + "  /* a" + eol + "  b */ int x; // c" + eol + "}" + eol;

        String out = redact(source);

        assertEquals(source.length(), out.length());
        assertEquals(source.replaceAll("[^\r\n]", " ").length(), out.replaceAll("[^\r\n]", " ").length());
        assertEquals(source.indexOf("int x;"), out.indexOf("int x;"));
        assertFalse(out.contains("first"));
        assertFalse(out.contains("// c"));
        assertEquals(source.chars().filter(c -> c == '\r' || c == '\n').count(),
                out.chars().filter(c -> c == '\r' || c == '\n').count());
    }

    @Test
    void unparsableSourceFailsClosed() {
        CommentRedactionException e = assertThrows(CommentRedactionException.class,
                () -> redact("class A { void m( { // oops"));

        assertTrue(e.getMessage().contains("LoginTest.java"), e.getMessage());
    }

    @Test
    void supportsOnlyJavaFiles() {
        assertTrue(redactor.supports(Path.of("src/A.java")));
        assertTrue(redactor.supports(Path.of("A.JAVA")));
        assertFalse(redactor.supports(Path.of("A.cs")));
        assertFalse(redactor.supports(null));
        assertFalse(redactor.supports(Path.of("A.jav")));
        assertEquals("java", redactor.pluginId());
    }

    @Test
    void isRegisteredWithServiceLoader() {
        boolean found = StreamSupport.stream(ServiceLoader.load(CommentRedactor.class).spliterator(), false)
                .anyMatch(r -> r instanceof JavaCommentRedactor);

        assertTrue(found);
    }

    private static long count(String s, char c) {
        return s.chars().filter(x -> x == c).count();
    }
}
