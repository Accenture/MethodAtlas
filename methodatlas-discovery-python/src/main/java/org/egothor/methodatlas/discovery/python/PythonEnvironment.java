package org.egothor.methodatlas.discovery.python;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects the Python runtime and records its version for audit logging.
 *
 * <p>
 * At most one detection attempt is made per instance.  If {@code python3
 * --version} (or {@code python --version} as a fallback) exits with a
 * non-zero code or is not found on the {@code PATH}, {@link #isAvailable()}
 * returns {@code false} and the Python plugin is disabled gracefully.
 * </p>
 *
 * <h2>Minimum version</h2>
 *
 * <p>
 * Python 3.8 or later is required; {@code ast.Node.end_lineno} — used by
 * the scanner script to compute per-function line ranges — was added in
 * Python 3.8.  When an older version is found the plugin logs a
 * {@code WARNING} and disables itself.
 * </p>
 *
 * <h2>Audit trail</h2>
 *
 * <p>
 * The detected Python version string is included in every worker-start log
 * line so that audit teams can trace exactly which runtime executed the
 * scanner script during any given MethodAtlas run.
 * </p>
 */
final class PythonEnvironment {

    private static final Logger LOG = Logger.getLogger(PythonEnvironment.class.getName());

    /** Minimum major version of Python required by this plugin. */
    /* default */ static final int MINIMUM_MAJOR_VERSION = 3;

    /** Minimum minor version of Python required by this plugin. */
    /* default */ static final int MINIMUM_MINOR_VERSION = 8;

    private final boolean available;
    private final String versionString;
    private final String executableName;

    /**
     * Detects the Python runtime by running {@code python3 --version}
     * (falling back to {@code python --version}).
     *
     * <p>
     * The detection result is logged once at {@code INFO} level when Python
     * is found, or at {@code WARNING} level when it is absent or too old.
     * </p>
     */
    /* default */ PythonEnvironment() {
        String[] candidates = { "python3", "python" };
        String found = null;
        String foundVersion = null;
        for (String candidate : candidates) {
            String version = detectVersion(candidate);
            if (version != null) {
                found = candidate;
                foundVersion = version;
                break;
            }
        }

        if (found == null) {
            this.available = false;
            this.versionString = "unavailable";
            this.executableName = "python3";
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("Python not found on PATH — Python discovery plugin is disabled. "
                        + "Install Python " + MINIMUM_MAJOR_VERSION + "." + MINIMUM_MINOR_VERSION
                        + " or later to enable Python scanning.");
            }
        } else {
            this.executableName = found;
            this.versionString = foundVersion;
            int[] parsed = parseMajorMinor(foundVersion);
            boolean meetsRequirement = parsed[0] > MINIMUM_MAJOR_VERSION
                    || (parsed[0] == MINIMUM_MAJOR_VERSION && parsed[1] >= MINIMUM_MINOR_VERSION);
            if (meetsRequirement) {
                this.available = true;
                if (LOG.isLoggable(Level.INFO)) {
                    LOG.log(Level.INFO, "Python detected: {0} (executable={1})",
                            new Object[] { versionString, executableName });
                }
            } else {
                this.available = false;
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.warning("Python " + foundVersion + " is below the minimum required version "
                            + MINIMUM_MAJOR_VERSION + "." + MINIMUM_MINOR_VERSION
                            + " — Python discovery plugin is disabled.");
                }
            }
        }
    }

    /**
     * Returns {@code true} when Python of a supported version is on the PATH.
     *
     * @return {@code true} when the Python plugin can be used
     */
    /* default */ boolean isAvailable() {
        return available;
    }

    /**
     * Returns the Python version string reported by {@code python3 --version}
     * (e.g. {@code "Python 3.11.4"}), or {@code "unavailable"}.
     *
     * @return version string; never {@code null}
     */
    /* default */ String versionString() {
        return versionString;
    }

    /**
     * Returns the Python executable name that was detected ({@code "python3"}
     * or {@code "python"}).
     *
     * @return executable name; never {@code null}
     */
    /* default */ String executableName() {
        return executableName;
    }

    // ── Private helpers ───────────────────────────────────────────────

    @SuppressWarnings("PMD.DoNotUseThreads")
    private static String detectVersion(String executable) {
        try {
            ProcessBuilder pb = new ProcessBuilder(executable, "--version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            int exitCode = proc.waitFor();
            if (exitCode != 0 || output == null || output.isBlank()) {
                return null;
            }
            return output.trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Parses major and minor version numbers from a Python version string
     * such as {@code "Python 3.11.4"}.  Returns {@code [0, 0]} on parse failure.
     *
     * @param version Python version string
     * @return two-element array {@code [major, minor]}
     */
    /* default */ static int[] parseMajorMinor(String version) {
        if (version == null || version.isEmpty()) {
            return new int[] { 0, 0 };
        }
        // Strip optional "Python " prefix
        String stripped = version.startsWith("Python ") ? version.substring(7) : version;
        try {
            int firstDot = stripped.indexOf('.');
            if (firstDot < 0) {
                return new int[] { Integer.parseInt(stripped.trim()), 0 };
            }
            int major = Integer.parseInt(stripped.substring(0, firstDot).trim());
            int secondDot = stripped.indexOf('.', firstDot + 1);
            String minorStr = secondDot < 0
                    ? stripped.substring(firstDot + 1)
                    : stripped.substring(firstDot + 1, secondDot);
            int minor = Integer.parseInt(minorStr.trim());
            return new int[] { major, minor };
        } catch (NumberFormatException e) {
            return new int[] { 0, 0 };
        }
    }
}
