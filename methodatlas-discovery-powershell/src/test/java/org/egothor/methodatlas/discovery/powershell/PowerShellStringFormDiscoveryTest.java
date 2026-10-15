package org.egothor.methodatlas.discovery.powershell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the discovered method set for PowerShell string forms (here-strings,
 * multi-line double-quoted strings, back-tick-escaped quotes) that the
 * redaction work added to {@code PowerShellTest.g4}. The expectations are the
 * semantically correct Pester method sets: text inside a string literal is never
 * a test.
 */
@Tag("unit")
class PowerShellStringFormDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void hereStrings_doNotHideOrInventTests() throws IOException {
        String source = String.join("\n",
                "Describe \"Suite\" {",
                "    It \"first\" {",
                "        $h = @\"",
                "It \"fake inside double here-string\" {",
                "\"@",
                "        $x = 1",
                "    }",
                "    It \"second\" -Tag \"t1\" {",
                "        $s = @'",
                "It 'fake inside single here-string' { } \" ' }",
                "'@",
                "    }",
                "    It \"third\" { }",
                "}",
                "");

        assertEquals(List.of("first", "second", "third"), discoverNames(source));
    }

    @Test
    void multiLineDoubleQuotedString_doesNotInventTests() throws IOException {
        String source = String.join("\n",
                "Describe \"Suite\" {",
                "    It \"multi\" {",
                "        $m = \"line one",
                "It \"fake in multi-line string\" { }",
                "line two\"",
                "    }",
                "    It \"after\" { }",
                "}",
                "");

        assertEquals(List.of("multi", "after"), discoverNames(source));
    }

    @Test
    void backtickEscapedQuotes_areKeptInsideStringsAndNames() throws IOException {
        String source = String.join("\n",
                "Describe \"Suite\" {",
                "    It \"has `\"quoted`\" name\" {",
                "        $s = \"a `\" It \"\"x\"\" { b\"",
                "    }",
                "    It \"plain\" { }",
                "}",
                "");

        List<String> names = discoverNames(source);

        assertEquals(2, names.size(), names.toString());
        assertEquals("plain", names.get(1));
    }

    private List<String> discoverNames(String source) throws IOException {
        Files.writeString(tempDir.resolve("Forms.Tests.ps1"), source);
        try (PowerShellTestDiscovery sut = new PowerShellTestDiscovery()) {
            return sut.discover(tempDir).map(DiscoveredMethod::method).collect(Collectors.toList());
        }
    }
}
