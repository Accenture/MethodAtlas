package org.egothor.methodatlas.discovery.python;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extracts the bundled {@code py-scanner.py} script from the JAR to a
 * temporary file so that a Python subprocess can execute it.
 *
 * <p>
 * The script is packaged as a JAR resource at
 * {@code /org/egothor/methodatlas/discovery/python/py-scanner.py}.
 * It is extracted once per JVM invocation to a temporary directory managed
 * by the OS; the temporary file is registered for deletion on JVM exit.
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

    private static final Logger LOG = Logger.getLogger(PythonScriptExtractor.class.getName());

    /** Classpath path of the bundled scanner script. */
    private static final String RESOURCE_PATH =
            "/org/egothor/methodatlas/discovery/python/py-scanner.py";

    private PythonScriptExtractor() {
        // utility class
    }

    /**
     * Extracts the {@code py-scanner.py} script from the JAR to a temporary
     * file and returns its path.
     *
     * @return path to the extracted script; the file will be deleted on JVM exit
     * @throws IOException if the resource cannot be found or the temp file
     *                     cannot be written
     */
    /* default */ static Path extractScript() throws IOException {
        try (InputStream in = PythonScriptExtractor.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IOException(
                        "py-scanner.py not found on classpath at: " + RESOURCE_PATH);
            }
            Path tmp = Files.createTempFile("methodatlas-py-scanner-", ".py");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("Extracted py-scanner.py to: " + tmp);
            }
            return tmp;
        }
    }
}
