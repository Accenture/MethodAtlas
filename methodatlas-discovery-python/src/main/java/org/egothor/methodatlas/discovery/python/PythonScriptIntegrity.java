package org.egothor.methodatlas.discovery.python;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verifies the integrity of the embedded Python scanner script and extracts it
 * to a temporary file ready for execution.
 *
 * <h2>Script provenance chain</h2>
 *
 * <ol>
 * <li>At build time, the Gradle build computes the SHA-256 of
 *     {@code py-scanner.py} and embeds it as the {@code Py-Scanner-SHA256}
 *     entry in the JAR {@code MANIFEST.MF}.</li>
 * <li>At runtime, this class reads the script from the classpath, re-computes
 *     its SHA-256, and compares it against the manifest value.  A mismatch
 *     causes an {@link IllegalStateException} — the plugin refuses to start
 *     rather than execute a potentially tampered script.</li>
 * <li>The verified script is extracted to a temporary file.  The path to that
 *     file is returned to the caller for use as the Python subprocess entry
 *     point.</li>
 * </ol>
 *
 * <h2>Audit logging</h2>
 *
 * <p>
 * After successful verification, the script version, full SHA-256, and the
 * path of the temporary extraction are logged at {@code INFO} level.  This
 * gives audit teams a full provenance trail: version → hash → executable
 * path.
 * </p>
 */
final class PythonScriptIntegrity {

    private static final Logger LOG = Logger.getLogger(PythonScriptIntegrity.class.getName());

    /** Classpath location of the bundled scanner script. */
    /* default */ static final String SCRIPT_RESOURCE =
            "/org/egothor/methodatlas/discovery/python/py-scanner.py";

    /** JAR manifest attribute carrying the script SHA-256. */
    /* default */ static final String MANIFEST_HASH_ATTR = "Py-Scanner-SHA256";

    /** JAR manifest attribute carrying the script version. */
    /* default */ static final String MANIFEST_VERSION_ATTR = "Py-Scanner-Version";

    private PythonScriptIntegrity() {
    }

    /**
     * Verifies the embedded script and extracts it to a temporary file.
     *
     * <p>
     * The temporary file is created in the default temporary-file directory
     * and is marked for deletion on JVM exit.
     * </p>
     *
     * @return absolute path to the extracted, verified script
     * @throws IllegalStateException if the script is not found on the
     *         classpath, if the hash embedded in the manifest does not match
     *         the computed hash of the script bytes, or if SHA-256 is
     *         unavailable (impossible in practice)
     * @throws IOException if writing the temporary file fails
     */
    /* default */ static Path extractAndVerify() throws IOException {
        byte[] scriptBytes = loadScript();
        String computedHash = sha256Hex(scriptBytes);
        String manifestVersion = readManifestAttribute(MANIFEST_VERSION_ATTR);
        String manifestHash = readManifestAttribute(MANIFEST_HASH_ATTR);

        verifyHash(manifestHash, computedHash);

        Path tempFile = extractToTemp(scriptBytes);

        if (LOG.isLoggable(Level.INFO)) {
            LOG.log(Level.INFO,
                    "Python scanner script verified — version={0}, sha256={1}, extracted={2}",
                    new Object[] { manifestVersion != null ? manifestVersion : "unknown",
                            computedHash, tempFile });
        }

        return tempFile;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Loads the script bytes from the classpath.
     *
     * @return raw script bytes
     * @throws IOException if the resource is not found or reading fails
     */
    private static byte[] loadScript() throws IOException {
        try (InputStream in = PythonScriptIntegrity.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException(
                        "py-scanner.py not found on classpath at "
                        + SCRIPT_RESOURCE
                        + ". Ensure the methodatlas-discovery-python JAR was built with "
                        + "'./gradlew :methodatlas-discovery-python:build'.");
            }
            return in.readAllBytes();
        }
    }

    /**
     * Reads an attribute from the JAR manifest that contains this class.
     *
     * @param attributeName manifest attribute name
     * @return attribute value, or {@code null} when not present or manifest
     *         cannot be read
     */
    private static String readManifestAttribute(String attributeName) {
        try (InputStream manifestStream = PythonScriptIntegrity.class
                .getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (manifestStream == null) {
                return null;
            }
            Manifest manifest = new Manifest(manifestStream);
            return manifest.getMainAttributes().getValue(attributeName);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Cannot read JAR manifest", e);
            }
            return null;
        }
    }

    /**
     * Compares the expected hash from the manifest against the computed hash
     * and throws if they differ.
     *
     * <p>
     * When the manifest does not contain a hash entry (e.g. during local
     * development before a full build), the check is skipped and a
     * {@code WARNING} is logged.
     * </p>
     *
     * @param manifestHash SHA-256 from the JAR manifest; may be {@code null}
     * @param computedHash SHA-256 freshly computed from the script bytes
     * @throws IllegalStateException if both hashes are present and differ
     */
    private static void verifyHash(String manifestHash, String computedHash) {
        if (manifestHash == null || manifestHash.isBlank()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("Python scanner script hash not found in JAR manifest — "
                        + "integrity verification skipped. "
                        + "This may indicate a non-release build. "
                        + "Computed sha256=" + computedHash);
            }
            return;
        }
        if (!manifestHash.equalsIgnoreCase(computedHash)) {
            throw new IllegalStateException(
                    "Python scanner script integrity check FAILED. "
                    + "Expected sha256=" + manifestHash
                    + ", computed sha256=" + computedHash
                    + ". The JAR may have been tampered with or corrupted. "
                    + "Rebuild the module to generate a fresh script hash.");
        }
    }

    /**
     * Writes {@code bytes} to a temporary file and marks it for deletion on
     * JVM exit.
     *
     * @param bytes script contents
     * @return path to the created temporary file
     * @throws IOException if the temporary file cannot be created or written
     */
    private static Path extractToTemp(byte[] bytes) throws IOException {
        Path tempFile = Files.createTempFile("methodatlas-py-scanner-", ".py");
        tempFile.toFile().deleteOnExit();
        try (OutputStream out = Files.newOutputStream(tempFile)) {
            out.write(bytes);
        }
        return tempFile;
    }

    /**
     * Computes the SHA-256 digest of {@code bytes} and returns it as a
     * lowercase hex string.
     *
     * @param bytes data to hash
     * @return 64-character lowercase hex SHA-256 digest
     * @throws IllegalStateException if SHA-256 is unavailable (impossible in
     *         practice; SHA-256 is mandated by the Java SE specification)
     */
    /* default */ static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
