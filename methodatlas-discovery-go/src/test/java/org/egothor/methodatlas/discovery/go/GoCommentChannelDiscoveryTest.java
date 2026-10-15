package org.egothor.methodatlas.discovery.go;

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
 * {@code -> channel(HIDDEN)} (and the added catch-all rule) in
 * {@code GoTest.g4}: the set of discovered methods and their line ranges must
 * be exactly what the {@code skip} grammar produced for the same text.
 */
@Tag("unit")
class GoCommentChannelDiscoveryTest {

    /** Representative source; line numbers below refer to this text. */
    static final String SOURCE = """
            // Package auth tests { not a brace
            package auth_test /* trailing } */

            import (
            \t"testing" // std {
            )

            /* func TestCommentedOut(t *testing.T) { */
            func TestLoginValid(t *testing.T) {
            \t// body comment { unbalanced
            \ts := "// not a comment {"
            \tr := '{'
            \tn := a / b
            \t_ = s /* } */
            \t_ = r + n
            }

            func helper() {}

            func TestLogout(t *testing.T) {
            \traw := `/* raw { */`
            \t_ = raw
            }
            """;

    /**
     * Discovery over {@link #SOURCE} yields the two real tests with the line
     * ranges recorded from the pre-change grammar; commented-out and helper
     * functions are not reported.
     *
     * @param dir JUnit-managed temporary directory
     * @throws IOException if the fixture cannot be written
     */
    @Test
    void discovery_isUnchangedByHiddenChannelComments(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("auth_test.go"), SOURCE);

        List<String> found;
        try (GoTestDiscovery discovery = new GoTestDiscovery()) {
            found = discovery.discover(dir)
                    .map(GoCommentChannelDiscoveryTest::describe)
                    .sorted()
                    .collect(Collectors.toList());
        }

        assertEquals(List.of("TestLoginValid@9-16", "TestLogout@20-23"), found);
    }

    private static String describe(DiscoveredMethod m) {
        return m.method() + "@" + m.beginLine() + "-" + m.endLine();
    }
}
