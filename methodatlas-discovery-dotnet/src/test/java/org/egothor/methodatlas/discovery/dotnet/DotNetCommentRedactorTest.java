package org.egothor.methodatlas.discovery.dotnet;

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
 * Tests for {@link DotNetCommentRedactor}: comments blank out, every string and
 * character literal form survives, preprocessor directives are kept, every
 * offset/line invariant holds and anything the lexer cannot classify fails
 * closed.
 */
@Tag("unit")
class DotNetCommentRedactorTest {

    private static final Path FILE = Path.of("LoginTests.cs");
    private final DotNetCommentRedactor redactor = new DotNetCommentRedactor();

    private static final String PII_SOURCE = String.join("\n",
            "// Copyright header: Jane Doe",
            "using System; /* owner jane.doe@acme.com */",
            "",
            "#region Helpers",
            "#if DEBUG // debug only, ask John",
            "#endif",
            "namespace Demo",
            "{",
            "    /// <summary>customer 4111-1111-1111-1111 +44 7700 900123</summary>",
            "    public class LoginTests",
            "    {",
            "        [Test] // inline note",
            "        public void TestLogin()",
            "        {",
            "            var u = \"http://x /* not a comment */\"; /* trailing */",
            "            var v = @\"// verbatim \"\" /* no */\";",
            "            var i = $\"http://{host}/{{x}} // no\";",
            "            var r = \"\"\"// raw /* no */\"\"\";",
            "            var c = '/';",
            "            /* multi",
            "               line secret */",
            "        }",
            "    }",
            "}",
            "#endregion",
            "// last line comment",
            "");

    @Test
    void supports_matchesCsSuffixOnly() {
        assertTrue(redactor.supports(Path.of("x/LoginTests.cs")));
        assertTrue(redactor.supports(Path.of("Run.CS")));
        assertFalse(redactor.supports(Path.of("Auth.java")));
        assertFalse(redactor.supports(Path.of("Auth.csx")));
        assertFalse(redactor.supports(null));
        assertFalse(redactor.supports(Path.of("/")));
        assertEquals("dotnet", redactor.pluginId());
    }

    @Test
    void redact_blanksLineDocAndBlockComments() throws CommentRedactionException {
        String out = redactor.redact(FILE, "int a; // c1\n/// doc\nint /* c2 */ b;\n");

        assertEquals("int a; " + " ".repeat(5) + "\n" + " ".repeat(7) + "\nint " + " ".repeat(8) + " b;\n", out);
    }

    @Test
    void redact_keepsMarkersInsideEveryLiteralForm() throws CommentRedactionException {
        String src = String.join("\n",
                "var a = \"// no\";",
                "var b = \"/* no */ \\\" //\";",
                "var c = @\"// no \"\" /* no */\";",
                "var d = $\"http://{x}\";",
                "var e = $@\"// no {x}\";",
                "var f = \"\"\"",
                "// raw /* no */",
                "\"\"\";",
                "var g = '/';",
                "var h = '\\u0041';",
                "");

        assertEquals(src, redactor.redact(FILE, src));
    }

    @Test
    void redact_keepsPreprocessorDirectivesAndBlanksTheirTrailingComment() throws CommentRedactionException {
        String src = "#if DEBUG // note\n#pragma warning disable \"a//b\" // x\n#define FOO\n";

        String out = redactor.redact(FILE, src);

        assertEquals("#if DEBUG " + " ".repeat(7) + "\n"
                + "#pragma warning disable \"a//b\" " + " ".repeat(4) + "\n#define FOO\n", out);
    }

    @Test
    void redact_blanksFreeTextOfRegionErrorAndWarningDirectivesButKeepsKeyword() throws CommentRedactionException {
        String src = "#region Jane Doe notes\n#endregion Jane Doe\n#error call 4111-1111-1111-1111\n"
                + "#warning ask jane.doe@acme.com\n#error\n";

        String out = redactor.redact(FILE, src);

        assertEquals("#region" + " ".repeat(" Jane Doe notes".length()) + "\n"
                + "#endregion" + " ".repeat(" Jane Doe".length()) + "\n"
                + "#error" + " ".repeat(" call 4111-1111-1111-1111".length()) + "\n"
                + "#warning" + " ".repeat(" ask jane.doe@acme.com".length()) + "\n#error\n", out);
        assertEquals(out, redactor.redact(FILE, out), "redaction must be idempotent");
    }

    @Test
    void redact_blanksPiiBearingCommentsCompletely() throws CommentRedactionException {
        String out = redactor.redact(FILE, PII_SOURCE);

        for (String pii : List.of("Jane Doe", "jane.doe@acme.com", "4111-1111-1111-1111", "7700 900123",
                "ask John", "inline note", "trailing */", "line secret", "last line comment")) {
            assertFalse(out.contains(pii), "must be blanked: " + pii);
        }
        assertTrue(out.contains("http://x /* not a comment */"));
        assertTrue(out.contains("// verbatim \"\" /* no */"));
        assertTrue(out.contains("http://{host}/{{x}} // no"));
        assertTrue(out.contains("// raw /* no */"));
        assertTrue(out.contains("#region"));
        assertFalse(out.contains("Helpers"), "region name is free text and must be blanked");
        assertInvariants(PII_SOURCE, out);
    }

    @Test
    void redact_preservesLengthLinesAndOffsets() throws CommentRedactionException {
        String out = redactor.redact(FILE, PII_SOURCE);

        assertInvariants(PII_SOURCE, out);
        assertEquals(PII_SOURCE.indexOf("TestLogin"), out.indexOf("TestLogin"));
        assertEquals(PII_SOURCE.indexOf("var c"), out.indexOf("var c"));
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
        assertEquals("int a;\n", redactor.redact(FILE, "int a;\n"));
        assertEquals("", redactor.redact(FILE, ""));
    }

    @Test
    void redact_keepsOffsetsAfterSupplementaryCharacters() throws CommentRedactionException {
        String src = "var s = \"😀\"; /* c */ x\n// 😀 tail\nfoo\n";

        String out = redactor.redact(FILE, src);

        assertEquals(src.length(), out.length());
        assertEquals(src.indexOf(" x\n"), out.indexOf(" x\n"));
        assertEquals(src.indexOf("foo"), out.indexOf("foo"));
        assertFalse(out.contains("tail"));
    }

    @Test
    void redact_acceptsUtf8Bom() throws CommentRedactionException {
        String src = "﻿using System; // c\n";

        assertEquals("﻿using System; " + " ".repeat(4) + "\n", redactor.redact(FILE, src));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "var s = \"unterminated // secret\n",
            "var s = @\"unterminated // secret\n",
            "var c = 'ab // secret\n",
            "int x = 1; /* open\nsecret\n",
            "#if X /* secret */\n#endif\n",
            "var s = $\"{x /* secret */}\";\n",
            "var s = $\"{x // secret\n}\";\n",
            "var s = \"\"\"\"a \"\"\" b\"\"\"\" // secret\n"})
    void redact_failsClosedOnConstructsTheLexerCannotClassify(String src) {
        assertThrows(CommentRedactionException.class, () -> redactor.redact(FILE, src));
    }

    @Test
    void redact_isRegisteredAsService() {
        List<CommentRedactor> found = StreamSupport
                .stream(ServiceLoader.load(CommentRedactor.class).spliterator(), false)
                .filter(r -> r instanceof DotNetCommentRedactor).toList();

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
