package org.egothor.methodatlas.discovery.go;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link GoTestDiscovery}.
 */
@Tag("unit")
class GoTestDiscoveryTest {

    /**
     * Copies the {@code auth_test.go.txt} fixture to a temp directory as a
     * {@code _test.go} file, runs discovery, and asserts exactly 3 test
     * methods are found with the expected names and non-trivial LOC.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_findsTestFunctions(@TempDir Path tempDir) throws IOException {
        copyFixture("auth_test.go.txt", tempDir, "auth_test.go");

        List<DiscoveredMethod> methods;
        try (GoTestDiscovery discovery = new GoTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertEquals(3, methods.size(), "expected exactly 3 test methods");

        Set<String> names = methods.stream()
                .map(DiscoveredMethod::method)
                .collect(Collectors.toSet());
        assertTrue(names.contains("TestLoginValid"),           "missing TestLoginValid");
        assertTrue(names.contains("TestLoginInvalidPassword"), "missing TestLoginInvalidPassword");
        assertTrue(names.contains("TestLoginEmptyUsername"),   "missing TestLoginEmptyUsername");

        for (DiscoveredMethod m : methods) {
            assertTrue(m.loc() > 1,
                    "expected LOC > 1 for " + m.method() + ", got " + m.loc());
            assertTrue(m.tags().isEmpty(),
                    "expected empty tags for " + m.method());
            assertFalse(m.fqcn().isBlank(),
                    "expected non-blank fqcn for " + m.method());
        }
    }

    /**
     * Verifies that {@code BenchmarkLogin} (not a test function) is not
     * included in discovery results.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_ignoresBenchmarks(@TempDir Path tempDir) throws IOException {
        copyFixture("auth_test.go.txt", tempDir, "auth_test.go");

        List<DiscoveredMethod> methods;
        try (GoTestDiscovery discovery = new GoTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        boolean hasBenchmark = methods.stream()
                .anyMatch(m -> "BenchmarkLogin".equals(m.method()));
        assertFalse(hasBenchmark, "BenchmarkLogin must not be discovered as a test");
    }

    /**
     * Verifies that a file without the {@code _test.go} suffix is ignored.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_returnsEmptyForNonTestFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("main.go"),
                "package main\n\nfunc main() {}\n");

        List<DiscoveredMethod> methods;
        try (GoTestDiscovery discovery = new GoTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertTrue(methods.isEmpty(), "expected no methods for non-test file");
    }

    /**
     * Verifies that scanning an empty directory returns an empty stream.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_returnsEmptyForEmptyDir(@TempDir Path tempDir) throws IOException {
        List<DiscoveredMethod> methods;
        try (GoTestDiscovery discovery = new GoTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertTrue(methods.isEmpty(), "expected no methods for empty directory");
    }

    /**
     * Verifies that the parser reports correct begin/end lines and LOC for a
     * multi-line test function.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_correctLocForMultiLineFunction(@TempDir Path tempDir) throws IOException {
        String source = "package main\n\n"
                + "import \"testing\"\n\n"
                + "func TestMultiLine(t *testing.T) {\n"  // line 5
                + "    t.Helper()\n"                        // line 6
                + "    t.Log(\"hello\")\n"                  // line 7
                + "}\n";                                    // line 8
        Files.writeString(tempDir.resolve("multi_test.go"), source);

        List<DiscoveredMethod> methods;
        try (GoTestDiscovery discovery = new GoTestDiscovery()) {
            methods = discovery.discover(tempDir).collect(Collectors.toList());
        }

        assertEquals(1, methods.size(), "expected exactly 1 test method");
        DiscoveredMethod m = methods.get(0);
        assertEquals("TestMultiLine", m.method());
        assertEquals(5, m.beginLine(), "expected beginLine=5");
        assertEquals(8, m.endLine(), "expected endLine=8");
        assertEquals(4, m.loc(), "expected LOC=4");
    }

    /**
     * Verifies that {@link GoTestDiscovery#buildFqcn(Path, Path, String)}
     * derives the dot-separated directory path relative to the root.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void buildFqcn_derivesFromDirectory(@TempDir Path tempDir) throws IOException {
        Path subPkg = tempDir.resolve("sub").resolve("pkg");
        Files.createDirectories(subPkg);
        Path file = subPkg.resolve("auth_test.go");
        Files.writeString(file, "package pkg\n");

        String fqcn = GoTestDiscovery.buildFqcn(file, tempDir, "pkg");
        assertEquals("sub.pkg", fqcn);
    }

    /**
     * Verifies that {@link GoTestDiscovery#buildFileStem(Path, Path)} strips
     * the {@code _test.go} suffix from the last path segment.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void buildFileStem_stripsTestGoSuffix(@TempDir Path tempDir) throws IOException {
        Path subPkg = tempDir.resolve("sub").resolve("pkg");
        Files.createDirectories(subPkg);
        Path file = subPkg.resolve("auth_test.go");
        Files.writeString(file, "package pkg\n");

        String stem = GoTestDiscovery.buildFileStem(file, tempDir);
        assertEquals("sub.pkg.auth", stem);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void copyFixture(String resourceName, Path targetDir,
                             String targetFileName) throws IOException {
        Path target = targetDir.resolve(targetFileName);
        try (InputStream in = getClass()
                .getResourceAsStream("/fixtures/" + resourceName)) {
            assertNotNull(in, "fixture not found: " + resourceName);
            Files.copy(in, target);
        }
    }
}
