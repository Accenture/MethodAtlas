package org.egothor.methodatlas.discovery.cobol;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Unit tests for {@link COBOLTestDiscovery}.
 */
@Tag("unit")
class COBOLTestDiscoveryTest {

    // ── MFUnit ────────────────────────────────────────────────────────

    /**
     * Verifies that MFU-TC- paragraphs are discovered from a .cbl fixture.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_mfunit_findsMfuTcParagraphs(@TempDir Path tempDir) throws IOException {
        copyFixture("auth_test.cbl.txt", tempDir, "auth_test.cbl");

        List<DiscoveredMethod> methods;
        try (COBOLTestDiscovery discovery = new COBOLTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertEquals(3, methods.size(), "expected exactly 3 MFU-TC paragraphs");

        Set<String> names = methods.stream()
                .map(DiscoveredMethod::method)
                .collect(Collectors.toSet());
        assertTrue(names.contains("MFU-TC-LOGIN-VALID"),        "missing MFU-TC-LOGIN-VALID");
        assertTrue(names.contains("MFU-TC-LOGIN-INVALID-PASS"), "missing MFU-TC-LOGIN-INVALID-PASS");
        assertTrue(names.contains("MFU-TC-LOGIN-EMPTY-USER"),   "missing MFU-TC-LOGIN-EMPTY-USER");
    }

    /**
     * Verifies that MFUnit methods have a positive LOC.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_mfunit_locIsPositive(@TempDir Path tempDir) throws IOException {
        copyFixture("auth_test.cbl.txt", tempDir, "auth_test.cbl");

        List<DiscoveredMethod> methods;
        try (COBOLTestDiscovery discovery = new COBOLTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        for (DiscoveredMethod m : methods) {
            assertTrue(m.loc() >= 1,
                    "expected LOC >= 1 for " + m.method() + ", got " + m.loc());
        }
    }

    // ── COBOL-Check ───────────────────────────────────────────────────

    /**
     * Verifies that TestCase declarations are discovered from a .cut fixture.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_cobolCheck_findsTestCases(@TempDir Path tempDir) throws IOException {
        copyFixture("auth.cut.txt", tempDir, "auth.cut");

        List<DiscoveredMethod> methods;
        try (COBOLTestDiscovery discovery = new COBOLTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertEquals(3, methods.size(), "expected exactly 3 TestCase declarations");

        Set<String> names = methods.stream()
                .map(DiscoveredMethod::method)
                .collect(Collectors.toSet());
        assertTrue(names.contains("valid login succeeds"),      "missing 'valid login succeeds'");
        assertTrue(names.contains("invalid password is rejected"), "missing 'invalid password is rejected'");
        assertTrue(names.contains("empty username is rejected"), "missing 'empty username is rejected'");
    }

    // ── General ───────────────────────────────────────────────────────

    /**
     * Verifies that scanning an empty directory returns an empty stream.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_returnsEmptyForEmptyDir(@TempDir Path tempDir) throws IOException {
        List<DiscoveredMethod> methods;
        try (COBOLTestDiscovery discovery = new COBOLTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertTrue(methods.isEmpty(), "expected no methods for empty directory");
    }

    /**
     * Verifies that a file with no MFU-TC paragraphs and no TestCase
     * declarations returns an empty stream.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_returnsEmptyForNonTestFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("helper.cbl"),
                "       IDENTIFICATION DIVISION.\n"
                + "       PROGRAM-ID. HELPER.\n"
                + "       PROCEDURE DIVISION.\n"
                + "       MAIN-PARA.\n"
                + "           STOP RUN.\n");

        List<DiscoveredMethod> methods;
        try (COBOLTestDiscovery discovery = new COBOLTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertTrue(methods.isEmpty(), "expected no methods for non-test COBOL file");
    }

    /**
     * Verifies {@link COBOLTestDiscovery#buildFileStem(Path, Path)}.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void buildFileStem_stripsSuffix(@TempDir Path tempDir) throws IOException {
        Path sub = tempDir.resolve("cobol").resolve("auth");
        Files.createDirectories(sub);
        Path file = sub.resolve("auth_test.cbl");
        Files.writeString(file, "");

        String stem = COBOLTestDiscovery.buildFileStem(file, tempDir);
        assertEquals("cobol.auth.auth_test", stem);
    }

    // ── Helpers ───────────────────────────────────────────────────────

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
