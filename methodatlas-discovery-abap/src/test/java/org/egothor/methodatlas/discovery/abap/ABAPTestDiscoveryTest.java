package org.egothor.methodatlas.discovery.abap;

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
 * Unit tests for {@link ABAPTestDiscovery}.
 */
@Tag("unit")
class ABAPTestDiscoveryTest {

    // ── ABAP Unit ─────────────────────────────────────────────────────

    /**
     * Copies the ABAP fixture to a temp directory and verifies that the
     * three FOR TESTING methods are discovered with correct names and LOC.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_abapUnit_findsForTestingMethods(@TempDir Path tempDir) throws IOException {
        copyFixture("zcl_auth_test.abap.txt", tempDir, "zcl_auth_test.abap");

        List<DiscoveredMethod> methods;
        try (ABAPTestDiscovery discovery = new ABAPTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertEquals(3, methods.size(), "expected exactly 3 FOR TESTING methods");

        Set<String> names = methods.stream()
                .map(DiscoveredMethod::method)
                .collect(Collectors.toSet());
        assertTrue(names.contains("TEST_VALID_LOGIN"),   "missing TEST_VALID_LOGIN");
        assertTrue(names.contains("TEST_INVALID_LOGIN"), "missing TEST_INVALID_LOGIN");
        assertTrue(names.contains("TEST_EMPTY_USER"),    "missing TEST_EMPTY_USER");
    }

    /**
     * Verifies that the {@code setup} method (without FOR TESTING) is not
     * included in the discovery results.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_abapUnit_ignoresNonTestMethods(@TempDir Path tempDir) throws IOException {
        copyFixture("zcl_auth_test.abap.txt", tempDir, "zcl_auth_test.abap");

        List<DiscoveredMethod> methods;
        try (ABAPTestDiscovery discovery = new ABAPTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        boolean hasSetup = methods.stream().anyMatch(m -> "SETUP".equals(m.method()));
        assertFalse(hasSetup, "setup method must not be discovered as a test");
    }

    /**
     * Verifies that discovered ABAP methods carry a non-blank FQCN
     * matching the class name.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_abapUnit_fqcnIsClassName(@TempDir Path tempDir) throws IOException {
        copyFixture("zcl_auth_test.abap.txt", tempDir, "zcl_auth_test.abap");

        List<DiscoveredMethod> methods;
        try (ABAPTestDiscovery discovery = new ABAPTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertFalse(methods.isEmpty());
        for (DiscoveredMethod m : methods) {
            assertEquals("ZCL_AUTH_TEST", m.fqcn(),
                    "FQCN must equal the class name in upper-case");
        }
    }

    /**
     * Verifies that LOC is greater than 1 for the multi-line test methods.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_abapUnit_locIsMultiLine(@TempDir Path tempDir) throws IOException {
        copyFixture("zcl_auth_test.abap.txt", tempDir, "zcl_auth_test.abap");

        List<DiscoveredMethod> methods;
        try (ABAPTestDiscovery discovery = new ABAPTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        for (DiscoveredMethod m : methods) {
            assertTrue(m.loc() > 1,
                    "expected LOC > 1 for " + m.method() + ", got " + m.loc());
        }
    }

    // ── ecATT ─────────────────────────────────────────────────────────

    /**
     * Verifies that FUNCTION blocks in an ecATT file are discovered as tests.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void discover_ecatt_findsFunctionBlocks(@TempDir Path tempDir) throws IOException {
        copyFixture("auth_login.ecl.txt", tempDir, "auth_login.ecl");

        List<DiscoveredMethod> methods;
        try (ABAPTestDiscovery discovery = new ABAPTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertEquals(3, methods.size(), "expected exactly 3 FUNCTION blocks");

        Set<String> names = methods.stream()
                .map(DiscoveredMethod::method)
                .collect(Collectors.toSet());
        assertTrue(names.contains("Z_AUTH_LOGIN_VALID"),   "missing Z_AUTH_LOGIN_VALID");
        assertTrue(names.contains("Z_AUTH_LOGIN_INVALID"), "missing Z_AUTH_LOGIN_INVALID");
        assertTrue(names.contains("Z_AUTH_LOGOUT"),        "missing Z_AUTH_LOGOUT");
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
        try (ABAPTestDiscovery discovery = new ABAPTestDiscovery()) {
            methods = discovery.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertTrue(methods.isEmpty(), "expected no methods for empty directory");
    }

    /**
     * Verifies that {@link ABAPTestDiscovery#buildFileStem(Path, Path)}
     * produces a correct dot-separated stem.
     *
     * @param tempDir JUnit-managed temporary directory
     * @throws IOException if file operations fail
     */
    @Test
    void buildFileStem_stripsSuffix(@TempDir Path tempDir) throws IOException {
        Path sub = tempDir.resolve("src").resolve("auth");
        Files.createDirectories(sub);
        Path file = sub.resolve("zcl_auth_test.abap");
        Files.writeString(file, "");

        String stem = ABAPTestDiscovery.buildFileStem(file, tempDir);
        assertEquals("src.auth.zcl_auth_test", stem);
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
