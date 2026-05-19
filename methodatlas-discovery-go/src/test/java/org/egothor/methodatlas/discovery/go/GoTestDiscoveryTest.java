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
import java.util.stream.Stream;

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

        GoTestDiscovery discovery = new GoTestDiscovery();
        List<DiscoveredMethod> methods = discovery.discover(tempDir)
                .collect(Collectors.toList());

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

        GoTestDiscovery discovery = new GoTestDiscovery();
        List<DiscoveredMethod> methods = discovery.discover(tempDir)
                .collect(Collectors.toList());

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

        GoTestDiscovery discovery = new GoTestDiscovery();
        List<DiscoveredMethod> methods = discovery.discover(tempDir)
                .collect(Collectors.toList());

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
        GoTestDiscovery discovery = new GoTestDiscovery();
        List<DiscoveredMethod> methods = discovery.discover(tempDir)
                .collect(Collectors.toList());

        assertTrue(methods.isEmpty(), "expected no methods for empty directory");
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

    /**
     * Verifies that {@link GoTestDiscovery#findFunctionEnd(List, int)}
     * correctly tracks brace depth and returns the one-based line of the
     * closing brace.
     */
    @Test
    void findFunctionEnd_tracksDepth() {
        List<String> lines = List.of(
                "func TestFoo(t *testing.T) {",  // line 1, depth → 1
                "    if true {",                  // line 2, depth → 2
                "        t.Log(\"hi\")",           // line 3
                "    }",                          // line 4, depth → 1
                "}"                               // line 5, depth → 0
        );

        int end = GoTestDiscovery.findFunctionEnd(lines, 0);
        assertEquals(5, end, "expected closing brace on line 5");
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
