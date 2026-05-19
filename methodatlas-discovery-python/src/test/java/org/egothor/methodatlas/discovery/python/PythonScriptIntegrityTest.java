package org.egothor.methodatlas.discovery.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PythonScriptIntegrity} static helpers.
 *
 * <p>
 * The {@link PythonScriptIntegrity#extractAndVerify()} method requires the
 * script to be present on the classpath and is covered by the integration test
 * that runs only when Python is available.  These tests target the pure-Java
 * utility methods.
 * </p>
 */
class PythonScriptIntegrityTest {

    @Test
    void sha256Hex_knownInput_returnsCorrectHash() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        byte[] empty = new byte[0];
        String hash = PythonScriptIntegrity.sha256Hex(empty);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }

    @Test
    void sha256Hex_asciiInput_returns64CharLowerHex() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        String hash = PythonScriptIntegrity.sha256Hex(data);
        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), "Expected lowercase hex, got: " + hash);
    }

    @Test
    void sha256Hex_twoEqualInputs_sameHash() {
        byte[] a = "methodatlas".getBytes(StandardCharsets.UTF_8);
        byte[] b = "methodatlas".getBytes(StandardCharsets.UTF_8);
        assertEquals(PythonScriptIntegrity.sha256Hex(a), PythonScriptIntegrity.sha256Hex(b));
    }

    @Test
    void sha256Hex_differentInputs_differentHashes() {
        byte[] a = "foo".getBytes(StandardCharsets.UTF_8);
        byte[] b = "bar".getBytes(StandardCharsets.UTF_8);
        var hashA = PythonScriptIntegrity.sha256Hex(a);
        var hashB = PythonScriptIntegrity.sha256Hex(b);
        assertTrue(!hashA.equals(hashB), "Different inputs must produce different hashes");
    }

    @Test
    void scriptResourceConstant_hasPyExtension() {
        assertTrue(PythonScriptIntegrity.SCRIPT_RESOURCE.endsWith(".py"),
                "Script resource path should end with .py");
    }

    @Test
    void manifestHashAttr_isExpectedKey() {
        assertEquals("Py-Scanner-SHA256", PythonScriptIntegrity.MANIFEST_HASH_ATTR);
    }

    @Test
    void manifestVersionAttr_isExpectedKey() {
        assertEquals("Py-Scanner-Version", PythonScriptIntegrity.MANIFEST_VERSION_ATTR);
    }
}
