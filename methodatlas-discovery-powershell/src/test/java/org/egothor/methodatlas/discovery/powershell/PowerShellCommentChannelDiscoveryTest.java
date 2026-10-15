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
 * Regression test for the comment rules moving from {@code -> skip} to
 * {@code -> channel(HIDDEN)} in {@code PowerShellTest.g4}: the set of
 * discovered methods, their tags and line ranges must be exactly what the
 * {@code skip} grammar produced for the same text.
 */
@Tag("unit")
class PowerShellCommentChannelDiscoveryTest {

    /** Representative source; line numbers below refer to this text. */
    static final String SOURCE = """
            # header comment with { brace
            <# block { help
               .SYNOPSIS #>
            Describe "Auth" {   # trailing {
                Context "login" {
                    It "accepts valid" -Tag "auth","smoke" {
                        $x = "# not a comment {"
                        $y = 'it''s <# nope #>'
                        <# inner } #>
                        $x | Should -Be $x
                    }
                    # It "commented out" { }
                    It "rejects invalid" {
                        $true | Should -BeTrue
                    }
                }
            }
            """;

    /**
     * Discovery over {@link #SOURCE} yields the two real {@code It} blocks
     * with tags and line ranges recorded from the pre-change grammar.
     *
     * @param dir JUnit-managed temporary directory
     * @throws IOException if the fixture cannot be written
     */
    @Test
    void discovery_isUnchangedByHiddenChannelComments(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Auth.Tests.ps1"), SOURCE);

        List<String> found;
        try (PowerShellTestDiscovery discovery = new PowerShellTestDiscovery()) {
            found = discovery.discover(dir)
                    .map(PowerShellCommentChannelDiscoveryTest::describe)
                    .sorted()
                    .collect(Collectors.toList());
        }

        assertEquals(List.of("accepts valid@6-11[auth, smoke]", "rejects invalid@13-15[]"), found);
    }

    private static String describe(DiscoveredMethod m) {
        return m.method() + "@" + m.beginLine() + "-" + m.endLine() + m.tags();
    }
}
