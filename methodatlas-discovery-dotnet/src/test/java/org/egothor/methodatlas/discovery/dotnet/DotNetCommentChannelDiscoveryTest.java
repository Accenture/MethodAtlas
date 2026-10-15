package org.egothor.methodatlas.discovery.dotnet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for the comment rules moving from {@code -> skip} to
 * {@code -> channel(HIDDEN)} in {@code CSharpTest.g4}: the set of discovered
 * methods, their tags and line ranges must be exactly what the {@code skip}
 * grammar produced for the same text.
 */
@Tag("unit")
class DotNetCommentChannelDiscoveryTest {

    /** Representative source; line numbers below refer to this text. */
    static final String SOURCE = """
            // header { comment
            using System; /* x */
            namespace Demo.Tests // ns {
            {
                /// <summary>XML doc { </summary>
                public class LoginTests
                {
                    /* [Test] public void Hidden() { } */
                    [Test] // attr note
                    [Category("auth")]
                    public void TestLogin()
                    {
                        var s = "// not comment {";
                        var v = @"/* verbatim { "" */";
                        var i = $"http://{host}/{{x}}";
                        var c = '{';
                        // closing } in comment
                    }

                    [Test]
                    public void TestLogout() { /* } */ }
                }
            }
            """;

    /**
     * Discovery over {@link #SOURCE} yields the two real tests with tags and
     * line ranges recorded from the pre-change grammar; the commented-out
     * method is not reported.
     *
     * @param dir JUnit-managed temporary directory
     * @throws Exception if the fixture cannot be written
     */
    @Test
    void discovery_isUnchangedByHiddenChannelComments(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("LoginTests.cs"), SOURCE);
        DotNetTestDiscovery discovery = new DotNetTestDiscovery();
        discovery.configure(new TestDiscoveryConfig(List.of(".cs"), Set.of(), Map.of()));

        List<String> found = discovery.discover(dir)
                .map(DotNetCommentChannelDiscoveryTest::describe)
                .sorted()
                .collect(Collectors.toList());

        assertEquals(List.of("TestLogin@9-18[auth]", "TestLogout@20-21[]"), found);
    }

    private static String describe(DiscoveredMethod m) {
        return m.method() + "@" + m.beginLine() + "-" + m.endLine() + m.tags();
    }
}
