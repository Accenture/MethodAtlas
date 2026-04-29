package org.egothor.methodatlas.discovery.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BundleIntegrity} static helpers.
 *
 * <p>
 * The {@link BundleIntegrity#extractAndVerify()} method requires the bundle to
 * be present on the classpath (built by the {@code bundleScanner} Gradle task)
 * and is not tested here — it is covered by the integration test that runs
 * only when Node.js is available.  These tests target the pure-Java utility
 * methods.
 * </p>
 */
class BundleIntegrityTest {

    @Test
    void sha256Hex_knownInput_returnsCorrectHash() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        byte[] empty = new byte[0];
        String hash = BundleIntegrity.sha256Hex(empty);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }

    @Test
    void sha256Hex_asciiInput_returns64CharLowerHex() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        String hash = BundleIntegrity.sha256Hex(data);
        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), "Expected lowercase hex, got: " + hash);
    }

    @Test
    void sha256Hex_twoEqualInputs_sameHash() {
        byte[] a = "methodatlas".getBytes(StandardCharsets.UTF_8);
        byte[] b = "methodatlas".getBytes(StandardCharsets.UTF_8);
        assertEquals(BundleIntegrity.sha256Hex(a), BundleIntegrity.sha256Hex(b));
    }

    @Test
    void sha256Hex_differentInputs_differentHashes() {
        byte[] a = "foo".getBytes(StandardCharsets.UTF_8);
        byte[] b = "bar".getBytes(StandardCharsets.UTF_8);
        var hashA = BundleIntegrity.sha256Hex(a);
        var hashB = BundleIntegrity.sha256Hex(b);
        assertTrue(!hashA.equals(hashB), "Different inputs must produce different hashes");
    }

    @Test
    void bundleResourceConstant_hasBundleJsExtension() {
        assertTrue(BundleIntegrity.BUNDLE_RESOURCE.endsWith(".js"),
                "Bundle resource path should end with .js");
    }

    @Test
    void manifestHashAttr_isExpectedKey() {
        assertEquals("TS-Scanner-Bundle-SHA256", BundleIntegrity.MANIFEST_HASH_ATTR);
    }

    @Test
    void manifestVersionAttr_isExpectedKey() {
        assertEquals("TS-Scanner-Bundle-Version", BundleIntegrity.MANIFEST_VERSION_ATTR);
    }
}
