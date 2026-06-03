package org.egothor.methodatlas.discovery.python;

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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link PythonTestDiscovery}.
 *
 * <p>Tests that invoke {@link PythonTestDiscovery#discover(Path)} require
 * Python 3.8 or later on the system PATH.  When Python is absent the tests
 * are skipped via {@code assumeTrue}; the static-helper tests
 * ({@code buildModulePath_*}) do not require Python and always run.</p>
 */
@Tag("unit")
class PythonTestDiscoveryTest {

    /** Detected once per test run; used by {@code assumeTrue} guards. */
    private static final boolean PYTHON_AVAILABLE = new PythonEnvironment().isAvailable();

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // discover() integration tests
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@link PythonTestDiscovery#discover} finds exactly the
     * expected test methods in a {@code test_*.py}-named file and does not
     * report non-test helpers.
     */
    @Test
    void discover_findsTestMethods_inTestPrefixFile() throws IOException {
        assumeTrue(PYTHON_AVAILABLE, "Python 3.8+ required for subprocess discovery");
        copyFixture("test_auth.py.txt", "test_auth.py");

        List<DiscoveredMethod> methods;
        try (PythonTestDiscovery sut = new PythonTestDiscovery()) {
            methods = sut.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertEquals(4, methods.size(),
                "Expected 3 class methods + 1 module-level function");

        boolean hasHelper = methods.stream()
                .anyMatch(m -> m.method().equals("helper_create_user"));
        assertFalse(hasHelper, "helper_create_user must not be reported");
    }

    /**
     * Verifies that {@link PythonTestDiscovery#discover} finds test methods in
     * a {@code *_test.py}-named file (suffix convention).
     */
    @Test
    void discover_findsTestMethods_inTestSuffixFile() throws IOException {
        assumeTrue(PYTHON_AVAILABLE, "Python 3.8+ required for subprocess discovery");
        copyFixture("security_test.py.txt", "security_test.py");

        List<DiscoveredMethod> methods;
        try (PythonTestDiscovery sut = new PythonTestDiscovery()) {
            methods = sut.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertEquals(2, methods.size(),
                "Expected 2 methods from security_test.py");
    }

    /**
     * Verifies that pytest.mark decorator names are captured as tags on the
     * discovered method.
     */
    @Test
    void discover_extractsTagsFromDecorators() throws IOException {
        assumeTrue(PYTHON_AVAILABLE, "Python 3.8+ required for subprocess discovery");
        copyFixture("test_auth.py.txt", "test_auth.py");

        List<DiscoveredMethod> methods;
        try (PythonTestDiscovery sut = new PythonTestDiscovery()) {
            methods = sut.discover(tempDir)
                    .collect(Collectors.toList());
        }

        DiscoveredMethod loginValid = findMethod(methods,
                "test_login_with_valid_credentials");
        assertNotNull(loginValid, "test_login_with_valid_credentials not found");
        assertTrue(loginValid.tags().contains("security"),
                "Expected 'security' tag on test_login_with_valid_credentials");

        DiscoveredMethod loginInvalid = findMethod(methods,
                "test_login_with_invalid_password");
        assertNotNull(loginInvalid, "test_login_with_invalid_password not found");
        assertTrue(loginInvalid.tags().contains("security"),
                "Expected 'security' tag on test_login_with_invalid_password");
        assertTrue(loginInvalid.tags().contains("slow"),
                "Expected 'slow' tag on test_login_with_invalid_password");
    }

    /**
     * Verifies that methods inside a {@code Test*} class have an FQCN that
     * ends with the class name.
     */
    @Test
    void discover_classMethodsGetClassFqcn() throws IOException {
        assumeTrue(PYTHON_AVAILABLE, "Python 3.8+ required for subprocess discovery");
        copyFixture("test_auth.py.txt", "test_auth.py");

        List<DiscoveredMethod> methods;
        try (PythonTestDiscovery sut = new PythonTestDiscovery()) {
            methods = sut.discover(tempDir)
                    .collect(Collectors.toList());
        }

        List<DiscoveredMethod> classMethods = methods.stream()
                .filter(m -> m.method().equals("test_login_with_valid_credentials")
                        || m.method().equals("test_login_with_invalid_password")
                        || m.method().equals("test_logout_clears_session"))
                .collect(Collectors.toList());

        assertEquals(3, classMethods.size(),
                "Expected exactly 3 methods from TestAuthService");

        for (DiscoveredMethod m : classMethods) {
            assertTrue(m.fqcn().endsWith(".TestAuthService"),
                    "FQCN '" + m.fqcn() + "' should end with '.TestAuthService'");
        }
    }

    /**
     * Verifies that a module-level test function has an FQCN that does NOT
     * include the class name.
     */
    @Test
    void discover_moduleLevelFunctionHasModuleFqcn() throws IOException {
        assumeTrue(PYTHON_AVAILABLE, "Python 3.8+ required for subprocess discovery");
        copyFixture("test_auth.py.txt", "test_auth.py");

        List<DiscoveredMethod> methods;
        try (PythonTestDiscovery sut = new PythonTestDiscovery()) {
            methods = sut.discover(tempDir)
                    .collect(Collectors.toList());
        }

        DiscoveredMethod rateLimiting = findMethod(methods,
                "test_rate_limiting_blocks_brute_force");
        assertNotNull(rateLimiting,
                "test_rate_limiting_blocks_brute_force not found");
        assertFalse(rateLimiting.fqcn().endsWith(".TestAuthService"),
                "Module-level function FQCN must not end with '.TestAuthService'");
    }

    /**
     * Verifies that a plain Python file whose name does not match pytest
     * conventions produces an empty result.
     */
    @Test
    void discover_ignoresNonTestFiles() throws IOException {
        assumeTrue(PYTHON_AVAILABLE, "Python 3.8+ required for subprocess discovery");
        Path utilsFile = tempDir.resolve("utils.py");
        Files.writeString(utilsFile,
                "def create_user(name):\n    return name\n\ndef delete_user(name):\n    pass\n");

        List<DiscoveredMethod> methods;
        try (PythonTestDiscovery sut = new PythonTestDiscovery()) {
            methods = sut.discover(tempDir)
                    .collect(Collectors.toList());
        }

        assertTrue(methods.isEmpty(),
                "Non-test file utils.py must produce no results");
    }

    // -------------------------------------------------------------------------
    // buildModulePath unit tests
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@link PythonTestDiscovery#buildModulePath} converts various
     * path structures to the expected dot-separated module path strings.
     */
    @Test
    void buildModulePath_convertsPathToDots() throws IOException {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);

        // File directly in root
        Path directFile = root.resolve("test_root.py");
        assertEquals("test_root",
                PythonTestDiscovery.buildModulePath(directFile, root),
                "File directly in root");

        // File one level deep
        Path authDir = root.resolve("auth");
        Files.createDirectories(authDir);
        Path authFile = authDir.resolve("test_auth.py");
        assertEquals("auth.test_auth",
                PythonTestDiscovery.buildModulePath(authFile, root),
                "File one level deep");

        // File two levels deep
        Path deepDir = authDir.resolve("unit");
        Files.createDirectories(deepDir);
        Path deepFile = deepDir.resolve("test_login.py");
        assertEquals("auth.unit.test_login",
                PythonTestDiscovery.buildModulePath(deepFile, root),
                "File two levels deep");

        // File with _test.py suffix
        Path suffixFile = authDir.resolve("security_test.py");
        assertEquals("auth.security_test",
                PythonTestDiscovery.buildModulePath(suffixFile, root),
                "File with _test.py suffix");
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /**
     * Copies a test fixture from the classpath into {@code tempDir} under the
     * given target file name.
     *
     * @param fixtureName  classpath resource name under {@code fixtures/}
     * @param targetName   file name to write in {@code tempDir}
     * @throws IOException if the fixture cannot be read or written
     */
    private void copyFixture(String fixtureName, String targetName)
            throws IOException {
        String resourcePath = "/fixtures/" + fixtureName;
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Fixture not found on classpath: " + resourcePath);
            Files.copy(in, tempDir.resolve(targetName));
        }
    }

    /**
     * Finds the first {@link DiscoveredMethod} in {@code methods} whose simple
     * method name equals {@code methodName}.
     *
     * @param methods    list to search
     * @param methodName simple method name to look for
     * @return matching method, or {@code null} if not found
     */
    private static DiscoveredMethod findMethod(
            List<DiscoveredMethod> methods, String methodName) {
        return methods.stream()
                .filter(m -> m.method().equals(methodName))
                .findFirst()
                .orElse(null);
    }
}
