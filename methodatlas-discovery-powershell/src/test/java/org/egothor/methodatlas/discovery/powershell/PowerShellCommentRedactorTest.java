package org.egothor.methodatlas.discovery.powershell;

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
 * Tests for {@link PowerShellCommentRedactor}: comments blank out, string and
 * here-string literals survive, {@code #requires} is kept, every offset/line
 * invariant holds and anything the lexer cannot classify fails closed.
 */
@Tag("unit")
class PowerShellCommentRedactorTest {

    private static final Path FILE = Path.of("Auth.Tests.ps1");
    private final PowerShellCommentRedactor redactor = new PowerShellCommentRedactor();

    private static final String PII_SOURCE = String.join("\n",
            "#requires -Version 5.1",
            "# Copyright header: Jane Doe",
            "<#",
            ".SYNOPSIS",
            "  customer 4111-1111-1111-1111 +44 7700 900123 jane.doe@acme.com",
            "#>",
            "Describe \"Auth\" {   # trailing note",
            "    It \"logs in\" -Tag \"auth\" {",
            "        $u = \"http://x # not a comment <# nor this #>\"",
            "        $v = 'it''s # not a comment'",
            "        $h = @'",
            "it's a here-string # still not a comment",
            "'@",
            "        $w = @\"",
            "multi \"line\" <# kept #>",
            "\"@",
            "        <# inner secret #> $u | Should -Be $u # end",
            "    }",
            "}",
            "# last line comment",
            "");

    @Test
    void supports_matchesPs1SuffixOnly() {
        assertTrue(redactor.supports(Path.of("x/Auth.Tests.ps1")));
        assertTrue(redactor.supports(Path.of("run.PS1")));
        assertFalse(redactor.supports(Path.of("Auth.java")));
        assertFalse(redactor.supports(Path.of("mod.psm1")));
        assertFalse(redactor.supports(null));
        assertFalse(redactor.supports(Path.of("/")));
        assertEquals("powershell", redactor.pluginId());
    }

    @Test
    void redact_blanksLineAndBlockComments() throws CommentRedactionException {
        String out = redactor.redact(FILE, "Describe \"x\" { # c1\n <# c2 #> It \"y\" { }\n}\n");

        assertEquals("Describe \"x\" { " + " ".repeat(4) + "\n " + " ".repeat(8) + " It \"y\" { }\n}\n", out);
    }

    @Test
    void redact_keepsMarkersInsideStringsAndHereStrings() throws CommentRedactionException {
        String src = String.join("\n",
                "$a = \"# no\"",
                "$b = '<# no #>'",
                "$c = \"line one # no",
                "line two <# no #>\"",
                "$d = @'",
                "it's # no",
                "'@",
                "$e = @\"",
                "say \"hi\" # no",
                "\"@",
                "");

        assertEquals(src, redactor.redact(FILE, src));
    }

    @Test
    void redact_blanksCommentAfterHereStringWithApostrophe() throws CommentRedactionException {
        String src = "$h = @'\nit's here\n'@\n# secret comment\n$x = 'a'\n";

        String out = redactor.redact(FILE, src);

        assertFalse(out.contains("secret"));
        assertTrue(out.contains("it's here"));
        assertTrue(out.contains("$x = 'a'"));
    }

    @Test
    void redact_keepsRequiresDirectivesButBlanksLookalikes() throws CommentRedactionException {
        String src = "#requires -Version 5\n  #Requires -Modules Pester\n# requires not a directive\n"
                + "$x = 1 #requires trailing\n";

        String out = redactor.redact(FILE, src);

        assertTrue(out.startsWith("#requires -Version 5\n  #Requires -Modules Pester\n"));
        assertFalse(out.contains("not a directive"));
        assertFalse(out.contains("trailing"));
        assertEquals(src.length(), out.length());
    }

    @Test
    void redact_blanksRequiresLookalikesThatDoNotMatchTheStrictGrammar() throws CommentRedactionException {
        String src = "#requires -Version 5 call Jane Doe\n#requires jane.doe@acme.com\n#requires\n"
                + "#requires -Modules Pester, @{ModuleName='Az';ModuleVersion='2.0'}\n"
                + "#requires -PSEdition Core -RunAsAdministrator\n"
                + "#requires -Assembly Foo.dll -ShellId Microsoft.PowerShell\n";

        String out = redactor.redact(FILE, src);

        assertFalse(out.contains("Jane"));
        assertFalse(out.contains("jane.doe"));
        assertTrue(out.contains("#requires -Modules Pester, @{ModuleName='Az';ModuleVersion='2.0'}\n"));
        assertTrue(out.contains("#requires -PSEdition Core -RunAsAdministrator\n"));
        assertTrue(out.contains("#requires -Assembly Foo.dll -ShellId Microsoft.PowerShell\n"));
        assertEquals(src.length(), out.length());
    }

    @Test
    void redact_keepsHashOutsideSubExpressionsOfExpandableStrings() throws CommentRedactionException {
        String src = "$a = \"issue #12 costs $(1+1) # not code\"\n$b = \"esc `$(# no) end\"\n"
                + "$c = \"$(Get-X) #tag\"\n";

        assertEquals(src, redactor.redact(FILE, src));
    }

    @Test
    void redact_failsClosedOnCommentInsideSubExpression() {
        for (String src : List.of(
                "$s = \"value $(Get-Thing # secret note\n) end\"\n",
                "$s = \"value $(Get-Thing <# secret #> ) end\"\n",
                "$s = \"x $((1+2) # secret\n) y\"\n",
                "$s = @\"\nhello $(Get-Thing # secret\n)\n\"@\n",
                "$s = \"unclosed $(Get-Thing # secret\"\n")) {
            assertThrows(CommentRedactionException.class, () -> redactor.redact(FILE, src), src);
        }
    }

    @Test
    void redact_blanksPiiBearingCommentsCompletely() throws CommentRedactionException {
        String out = redactor.redact(FILE, PII_SOURCE);

        for (String pii : List.of("Jane Doe", "jane.doe@acme.com", "4111-1111-1111-1111", "7700 900123",
                "trailing note", "inner secret", "last line comment", "SYNOPSIS")) {
            assertFalse(out.contains(pii), "must be blanked: " + pii);
        }
        assertTrue(out.contains("#requires -Version 5.1"));
        assertTrue(out.contains("http://x # not a comment <# nor this #>"));
        assertTrue(out.contains("it's a here-string # still not a comment"));
        assertTrue(out.contains("multi \"line\" <# kept #>"));
        assertInvariants(PII_SOURCE, out);
    }

    @Test
    void redact_preservesLengthLinesAndOffsets() throws CommentRedactionException {
        String out = redactor.redact(FILE, PII_SOURCE);

        assertInvariants(PII_SOURCE, out);
        assertEquals(PII_SOURCE.indexOf("Describe"), out.indexOf("Describe"));
        assertEquals(PII_SOURCE.indexOf("Should"), out.indexOf("Should"));
    }

    @Test
    void redact_keepsCrlfInsideBlockComments() throws CommentRedactionException {
        String src = "a <# x\r\ny\rz #> b\r\n";

        assertEquals("a     \r\n \r     b\r\n", redactor.redact(FILE, src));
    }

    @Test
    void redact_isIdempotent() throws CommentRedactionException {
        String once = redactor.redact(FILE, PII_SOURCE);

        assertEquals(once, redactor.redact(FILE, once));
    }

    @Test
    void redact_returnsInputWhenThereIsNoComment() throws CommentRedactionException {
        assertEquals("$x = 1\n", redactor.redact(FILE, "$x = 1\n"));
        assertEquals("", redactor.redact(FILE, ""));
    }

    @Test
    void redact_keepsOffsetsAfterSupplementaryCharacters() throws CommentRedactionException {
        String src = "$s = \"😀\" <# c #> x\n# 😀 tail\nfoo\n";

        String out = redactor.redact(FILE, src);

        assertEquals(src.length(), out.length());
        assertEquals(src.indexOf(" x\n"), out.indexOf(" x\n"));
        assertEquals(src.indexOf("foo"), out.indexOf("foo"));
        assertFalse(out.contains("tail"));
    }

    @Test
    void redact_isCaseInsensitiveAndAcceptsOperators() throws CommentRedactionException {
        String src = "DESCRIBE 'x' { $a -eq $b | Out-Null; @(1,2) } # c\n";

        assertEquals("DESCRIBE 'x' { $a -eq $b | Out-Null; @(1,2) }" + " ".repeat(4) + "\n",
                redactor.redact(FILE, src));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "$s = \"unterminated # secret\n",
            "$s = 'unterminated # secret\n",
            "$h = @'\nnever closed # secret\n",
            "$x = 1 <# open\nsecret\n",
            "echo `\"hi # secret\n",
            "$s = 'it’s' # secret\n",
            "$s = ‘abc # secret\n",
            "$s = \"a ” b\" # secret\n"})
    void redact_failsClosedOnConstructsTheLexerCannotClassify(String src) {
        assertThrows(CommentRedactionException.class, () -> redactor.redact(FILE, src));
    }

    @Test
    void redact_acceptsBacktickLineContinuationAndSmartQuotesInDoubleQuotedText() throws CommentRedactionException {
        String src = "Get-Item `\n  -Path \"it’s\" # c\n";

        assertEquals("Get-Item `\n  -Path \"it’s\" " + " ".repeat(3) + "\n", redactor.redact(FILE, src));
    }

    @Test
    void redact_isRegisteredAsService() {
        List<CommentRedactor> found = StreamSupport
                .stream(ServiceLoader.load(CommentRedactor.class).spliterator(), false)
                .filter(r -> r instanceof PowerShellCommentRedactor).toList();

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
