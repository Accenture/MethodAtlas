package org.egothor.methodatlas.discovery.powershell;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PowerShellTestDiscovery}.
 *
 * <p>Each test uses a temporary directory so that filesystem state does not
 * leak between test cases. The Pester fixture ({@code Auth.Tests.ps1.txt})
 * is read from the test classpath and copied to the temp dir under its real
 * {@code .Tests.ps1} name before each test that needs it.</p>
 */
@Tag("unit")
class PowerShellTestDiscoveryTest {

    @TempDir
    Path tempDir;

    // ── discover() integration tests ─────────────────────────────────────────

    /**
     * Verifies that {@link PowerShellTestDiscovery#discover} finds all four
     * {@code It} blocks in the fixture file.
     */
    @Test
    void discover_findsItBlocks() throws IOException {
        copyFixture("Auth.Tests.ps1");

        PowerShellTestDiscovery sut = new PowerShellTestDiscovery();
        List<DiscoveredMethod> methods = sut.discover(tempDir).collect(Collectors.toList());

        assertEquals(4, methods.size(), "Expected exactly 4 It blocks");
    }

    /**
     * Verifies that the {@code method} field of each {@link DiscoveredMethod}
     * matches the quoted string inside the corresponding {@code It "..."} block.
     */
    @Test
    void discover_extractsMethodNames() throws IOException {
        copyFixture("Auth.Tests.ps1");

        PowerShellTestDiscovery sut = new PowerShellTestDiscovery();
        List<String> names = sut.discover(tempDir)
                .map(DiscoveredMethod::method)
                .collect(Collectors.toList());

        assertTrue(names.contains("accepts valid credentials"),
                "Expected 'accepts valid credentials' in names: " + names);
        assertTrue(names.contains("rejects invalid password"),
                "Expected 'rejects invalid password' in names: " + names);
        assertTrue(names.contains("rejects empty username"),
                "Expected 'rejects empty username' in names: " + names);
        assertTrue(names.contains("creates audit log entry on login"),
                "Expected 'creates audit log entry on login' in names: " + names);
    }

    /**
     * Verifies that tags declared with {@code -Tag} on the {@code It} line are
     * extracted correctly.
     *
     * <ul>
     *   <li>{@code "rejects invalid password"} → {@code ["negative"]}</li>
     *   <li>{@code "rejects empty username"} → {@code ["negative", "boundary"]}</li>
     * </ul>
     */
    @Test
    void discover_extractsTagsFromItLine() throws IOException {
        copyFixture("Auth.Tests.ps1");

        PowerShellTestDiscovery sut = new PowerShellTestDiscovery();
        List<DiscoveredMethod> methods = sut.discover(tempDir).collect(Collectors.toList());

        DiscoveredMethod rejectsInvalid = findByName(methods, "rejects invalid password");
        assertEquals(List.of("negative"), rejectsInvalid.tags(),
                "Expected tags [negative] for 'rejects invalid password'");

        DiscoveredMethod rejectsEmpty = findByName(methods, "rejects empty username");
        assertEquals(List.of("negative", "boundary"), rejectsEmpty.tags(),
                "Expected tags [negative, boundary] for 'rejects empty username'");
    }

    /**
     * Verifies that an {@code It} block without a {@code -Tag} switch has an
     * empty tag list.
     */
    @Test
    void discover_noTagsForUntaggedIt() throws IOException {
        copyFixture("Auth.Tests.ps1");

        PowerShellTestDiscovery sut = new PowerShellTestDiscovery();
        List<DiscoveredMethod> methods = sut.discover(tempDir).collect(Collectors.toList());

        DiscoveredMethod untagged = findByName(methods, "accepts valid credentials");
        assertTrue(untagged.tags().isEmpty(),
                "Expected no tags for 'accepts valid credentials', got: " + untagged.tags());
    }

    /**
     * Verifies that a plain {@code .ps1} file without the {@code .Tests.ps1}
     * suffix is ignored by the scanner.
     */
    @Test
    void discover_ignoresNonTestFiles() throws IOException {
        Files.writeString(tempDir.resolve("helper.ps1"), "function Get-Helper { }");

        PowerShellTestDiscovery sut = new PowerShellTestDiscovery();
        List<DiscoveredMethod> methods = sut.discover(tempDir).collect(Collectors.toList());

        assertTrue(methods.isEmpty(),
                "Expected no methods from helper.ps1, got: " + methods.size());
    }

    /**
     * Verifies that {@link PowerShellTestDiscovery#discover} returns an empty
     * stream when the root path is not a directory.
     */
    @Test
    void discover_returnsEmptyForNonDirectory() throws IOException {
        Path notADir = tempDir.resolve("not-a-dir.txt");
        Files.writeString(notADir, "");

        PowerShellTestDiscovery sut = new PowerShellTestDiscovery();
        List<DiscoveredMethod> methods = sut.discover(notADir).collect(Collectors.toList());

        assertTrue(methods.isEmpty(), "Expected empty stream for non-directory root");
    }

    // ── Static helper unit tests ──────────────────────────────────────────────

    /**
     * Verifies that {@link PowerShellTestDiscovery#extractTags(String)} parses
     * a line with multiple {@code -Tag} values correctly.
     */
    @Test
    void extractTags_parsesMultipleTags() {
        String line = "        It \"rejects empty username\" -Tag \"negative\", \"boundary\" {";
        List<String> tags = PowerShellTestDiscovery.extractTags(line);
        assertEquals(List.of("negative", "boundary"), tags);
    }

    /**
     * Verifies that {@link PowerShellTestDiscovery#extractTags(String)} returns
     * an empty list when there is no {@code -Tag} switch on the line.
     */
    @Test
    void extractTags_returnsEmptyWhenNoTag() {
        String line = "        It \"accepts valid credentials\" {";
        List<String> tags = PowerShellTestDiscovery.extractTags(line);
        assertTrue(tags.isEmpty(), "Expected empty list, got: " + tags);
    }

    /**
     * Verifies that {@link PowerShellTestDiscovery#extractTags(String)} handles
     * a single-quoted tag value.
     */
    @Test
    void extractTags_handlesSingleQuotedTag() {
        String line = "        It 'my test' -Tag 'integration' {";
        List<String> tags = PowerShellTestDiscovery.extractTags(line);
        assertEquals(List.of("integration"), tags);
    }

    /**
     * Verifies that {@link PowerShellTestDiscovery#buildFileStem} strips the
     * {@code .Tests.ps1} suffix and produces the expected dot-separated stem.
     */
    @Test
    void buildFileStem_stripsTestsPs1Suffix() {
        Path root = tempDir;
        Path file = root.resolve("Auth.Tests.ps1");
        String stem = PowerShellTestDiscovery.buildFileStem(file, root);
        assertEquals("Auth", stem);
    }

    /**
     * Verifies that {@link PowerShellTestDiscovery#buildFileStem} produces a
     * dot-separated path for a file in a subdirectory.
     */
    @Test
    void buildFileStem_includesSubdirectory() {
        Path root = tempDir;
        Path file = root.resolve("auth").resolve("Login.Tests.ps1");
        String stem = PowerShellTestDiscovery.buildFileStem(file, root);
        assertEquals("auth.Login", stem);
    }

    /**
     * Verifies that {@link PowerShellTestDiscovery#buildFqcn} returns only the
     * filename stem when the file is in the root directory.
     */
    @Test
    void buildFqcn_fileInRoot() {
        Path root = tempDir;
        Path file = root.resolve("Auth.Tests.ps1");
        String fqcn = PowerShellTestDiscovery.buildFqcn(file, root);
        assertEquals("Auth", fqcn);
    }

    /**
     * Verifies that {@link PowerShellTestDiscovery#buildFqcn} prefixes the
     * parent directory when the file is in a subdirectory.
     */
    @Test
    void buildFqcn_fileInSubdirectory() {
        Path root = tempDir;
        Path file = root.resolve("modules").resolve("auth").resolve("Auth.Tests.ps1");
        String fqcn = PowerShellTestDiscovery.buildFqcn(file, root);
        assertEquals("modules.auth.Auth", fqcn);
    }

    /**
     * Verifies that {@link PowerShellTestDiscovery#findItBlockEnd} returns the
     * 1-based line number of the closing brace of a simple block.
     */
    @Test
    void findItBlockEnd_simpleBlock() {
        List<String> lines = List.of(
                "It \"test\" {",          // idx 0
                "    $x = 1",              // idx 1
                "}"                        // idx 2
        );
        int end = PowerShellTestDiscovery.findItBlockEnd(lines, 0);
        assertEquals(3, end, "Expected end at line 3 (1-based)");
    }

    /**
     * Verifies that {@link PowerShellTestDiscovery#findItBlockEnd} handles
     * nested braces (e.g. a script block inside the {@code It} body).
     */
    @Test
    void findItBlockEnd_nestedBraces() {
        List<String> lines = List.of(
                "It \"test\" {",           // idx 0
                "    { $x = 1 } | Should -Throw",  // idx 1
                "}"                        // idx 2
        );
        int end = PowerShellTestDiscovery.findItBlockEnd(lines, 0);
        assertEquals(3, end, "Expected end at line 3 (1-based)");
    }

    /**
     * Verifies that {@link PowerShellTestDiscovery#findItBlockEnd} falls back
     * to the start line + 1 when the {@code It} has no body brace.
     */
    @Test
    void findItBlockEnd_noBodyFallback() {
        List<String> lines = List.of("It \"test\"");
        int end = PowerShellTestDiscovery.findItBlockEnd(lines, 0);
        assertEquals(1, end, "Expected fallback end at line 1 (1-based, i.e. startIdx+1)");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Copies the named fixture from the test classpath into
     * {@code tempDir} under the given filename.
     *
     * @param filename name to use for the copy in {@code tempDir}
     * @throws IOException if the resource cannot be read or written
     */
    private void copyFixture(String filename) throws IOException {
        String resourcePath = "fixtures/Auth.Tests.ps1.txt";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Fixture resource not found: " + resourcePath);
            Files.copy(in, tempDir.resolve(filename));
        }
    }

    /**
     * Finds a {@link DiscoveredMethod} by its method name in the given list.
     *
     * @param methods list to search
     * @param name    method name to look up
     * @return the matching method
     * @throws AssertionError when no method with that name is found
     */
    private static DiscoveredMethod findByName(List<DiscoveredMethod> methods, String name) {
        return methods.stream()
                .filter(m -> name.equals(m.method()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No method named '" + name + "' in " + methods));
    }
}
