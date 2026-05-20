package org.egothor.methodatlas.gui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SourceWriteBackSupport} correctly identifies
 * languages eligible for source write-back and reports the supported
 * languages label used by the GUI tooltip and the save-all error dialog.
 *
 * <p>
 * The tests rely on the ServiceLoader registrations shipped by the
 * {@code methodatlas-discovery-jvm} and {@code methodatlas-discovery-dotnet}
 * modules; every other discovery plugin ships no {@link SourcePatcher} and
 * therefore must be rejected.
 * </p>
 */
@Tag("unit")
class SourceWriteBackSupportTest {

    private static final TestDiscoveryConfig DEFAULT_CONFIG = new TestDiscoveryConfig(
            List.of(),
            Set.of("Test"),
            Map.of());

    @Test
    void supports_javaFile_returnsTrue() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        assertTrue(sut.supports(Path.of("src", "test", "java", "FooTest.java")),
                "Java test files must be supported (JVM patcher is present)");
    }

    @Test
    void supports_csharpFile_returnsTrue() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        assertTrue(sut.supports(Path.of("src", "test", "FooTest.cs")),
                "C# test files must be supported (dotnet patcher is present)");
    }

    @Test
    void supports_pythonFile_returnsFalse() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        assertFalse(sut.supports(Path.of("tests", "test_login.py")),
                "Python files must not be eligible for source write-back");
    }

    @Test
    void supports_goFile_returnsFalse() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        assertFalse(sut.supports(Path.of("auth", "auth_test.go")),
                "Go files must not be eligible for source write-back");
    }

    @Test
    void supports_typescriptFile_returnsFalse() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        assertFalse(sut.supports(Path.of("src", "auth.test.ts")),
                "TypeScript files must not be eligible for source write-back");
    }

    @Test
    void supports_powerShellFile_returnsFalse() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        assertFalse(sut.supports(Path.of("tests", "Auth.Tests.ps1")),
                "PowerShell files must not be eligible for source write-back");
    }

    @Test
    void supports_abapFile_returnsFalse() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        assertFalse(sut.supports(Path.of("zcl_auth_test.abap")),
                "ABAP files must not be eligible for source write-back");
    }

    @Test
    void supports_cobolFile_returnsFalse() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        assertFalse(sut.supports(Path.of("auth_test.cbl")),
                "COBOL files must not be eligible for source write-back");
    }

    @Test
    void supports_nullPath_returnsFalse() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        assertFalse(sut.supports(null), "null path must not be considered supported");
    }

    @Test
    void findPatcher_javaFile_returnsJavaPatcher() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        SourcePatcher patcher = sut.findPatcher(Path.of("FooTest.java"));
        assertNotNull(patcher, "expected Java patcher for .java file");
        assertEquals("java", patcher.pluginId(),
                "expected pluginId() == java, got " + patcher.pluginId());
    }

    @Test
    void findPatcher_unsupportedFile_returnsNull() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        assertNull(sut.findPatcher(Path.of("auth_test.go")),
                "no patcher should handle .go files");
    }

    @Test
    void supportedLanguagesLabel_listsJavaAndCSharp() {
        SourceWriteBackSupport sut = new SourceWriteBackSupport(DEFAULT_CONFIG);
        String label = sut.supportedLanguagesLabel();
        assertNotNull(label);
        assertTrue(label.contains("Java"), "label should mention Java, got: " + label);
        assertTrue(label.contains("C#"), "label should mention C#, got: " + label);
    }
}
