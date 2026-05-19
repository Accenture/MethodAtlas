package org.egothor.methodatlas.discovery.python;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Extracts the bundled {@code py-scanner.py} script from the JAR to a
 * temporary file so that a Python subprocess can execute it.
 *
 * <p>
 * Extraction is delegated to {@link PythonScriptIntegrity}, which verifies the
 * SHA-256 of the script against the value embedded in the JAR manifest before
 * writing the temporary file.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * {@link #extractScript()} is called under the pool-init lock in
 * {@link PythonTestDiscovery} and is therefore not required to be
 * thread-safe itself.
 * </p>
 */
final class PythonScriptExtractor {

    private PythonScriptExtractor() {
    }

    /**
     * Verifies and extracts the {@code py-scanner.py} script from the JAR to
     * a temporary file and returns its path.
     *
     * @return path to the extracted, verified script; the file will be deleted
     *         on JVM exit
     * @throws IllegalStateException if the script's SHA-256 does not match the
     *         value recorded in the JAR manifest
     * @throws IOException if the resource cannot be found or the temp file
     *                     cannot be written
     */
    /* default */ static Path extractScript() throws IOException {
        return PythonScriptIntegrity.extractAndVerify();
    }
}
