package org.egothor.methodatlas.discovery.typescript;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects the Node.js runtime and records its version for audit logging.
 *
 * <p>
 * At most one detection attempt is made per JVM lifetime (the result is
 * cached).  If {@code node --version} exits with a non-zero code or is not
 * found on the {@code PATH}, {@link #isAvailable()} returns {@code false} and
 * the TypeScript plugin is disabled gracefully.
 * </p>
 *
 * <h2>Minimum version</h2>
 *
 * <p>
 * Node.js 18 or later is required; versions below 18 lack the stable
 * {@code --experimental-permission} flag needed for filesystem sandboxing and
 * may have incompatible ESM / module-resolution behaviour.  When an older
 * version is found the plugin logs a {@code WARNING} and disables itself.
 * </p>
 *
 * <h2>Audit trail</h2>
 *
 * <p>
 * The detected Node.js version string is included in every worker-start log
 * line so that audit teams can trace exactly which runtime executed the
 * scanner bundle during any given MethodAtlas run.
 * </p>
 */
final class NodeEnvironment {

    private static final Logger LOG = Logger.getLogger(NodeEnvironment.class.getName());

    /** Minimum major version of Node.js required by this plugin. */
    /* default */ static final int MINIMUM_MAJOR_VERSION = 18;

    /**
     * Major version at which the Node.js permission model becomes available.
     * Workers are sandboxed with {@code --experimental-permission} when running
     * on this version or above.
     */
    /* default */ static final int PERMISSION_MODEL_VERSION = 20;

    /**
     * Major version at which the permission model was promoted to stable and
     * the flag was renamed from {@code --experimental-permission} to
     * {@code --permission}.
     */
    /* default */ static final int PERMISSION_STABLE_VERSION = 22;

    private final boolean available;
    private final String versionString;
    private final int majorVersion;
    private final boolean permissionModelSupported;

    /**
     * Detects the Node.js runtime by running {@code node --version}.
     *
     * <p>
     * The detection result is logged once at {@code INFO} level when Node.js is
     * found, or at {@code WARNING} level when it is absent or too old.
     * </p>
     */
    /* default */ NodeEnvironment() {
        String detected = detectVersion();
        if (detected == null) {
            this.available = false;
            this.versionString = "unavailable";
            this.majorVersion = 0;
            this.permissionModelSupported = false;
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("Node.js not found on PATH — TypeScript discovery plugin is disabled. "
                        + "Install Node.js " + MINIMUM_MAJOR_VERSION + " or later to enable TypeScript scanning.");
            }
        } else {
            this.versionString = detected;
            this.majorVersion = parseMajorVersion(detected);
            if (majorVersion < MINIMUM_MAJOR_VERSION) {
                this.available = false;
                this.permissionModelSupported = false;
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.warning("Node.js " + detected + " is below the minimum required version "
                            + MINIMUM_MAJOR_VERSION + " — TypeScript discovery plugin is disabled.");
                }
            } else {
                this.available = true;
                this.permissionModelSupported = majorVersion >= PERMISSION_MODEL_VERSION;
                if (LOG.isLoggable(Level.INFO)) {
                    LOG.log(Level.INFO, "Node.js detected: {0} (major={1}, permission-model={2})",
                            new Object[] { versionString, majorVersion, permissionModelSupported });
                }
            }
        }
    }

    /**
     * Returns {@code true} when Node.js of a supported version is on the PATH.
     *
     * @return {@code true} when the TypeScript plugin can be used
     */
    /* default */ boolean isAvailable() {
        return available;
    }

    /**
     * Returns the Node.js version string reported by {@code node --version}
     * (e.g. {@code "v20.11.0"}), or {@code "unavailable"} when Node.js is not
     * found.
     *
     * @return version string; never {@code null}
     */
    /* default */ String versionString() {
        return versionString;
    }

    /**
     * Returns {@code true} when Node.js supports the permission model
     * (Node.js 20 or later).
     *
     * <p>
     * When this returns {@code true}, workers are started with a permission
     * flag and {@code --allow-fs-read} arguments to restrict the worker
     * process to reading only the directories being scanned.  The exact flag
     * name is determined by {@link #permissionFlagName()}.
     * </p>
     *
     * @return {@code true} when file-system sandboxing is available
     */
    /* default */ boolean isPermissionModelSupported() {
        return permissionModelSupported;
    }

    /**
     * Returns the correct Node.js permission flag for the detected version.
     *
     * <p>
     * The flag was renamed from {@code --experimental-permission} (Node.js
     * 20–21) to {@code --permission} (Node.js 22 and later) when the
     * permission model was promoted to stable.  Using the wrong flag name
     * causes Node.js to exit with an unrecognised-option error.
     * </p>
     *
     * @return {@code "--permission"} for Node.js 22 or later;
     *         {@code "--experimental-permission"} for Node.js 20–21
     */
    /* default */ String permissionFlagName() {
        return majorVersion >= PERMISSION_STABLE_VERSION
                ? "--permission"
                : "--experimental-permission";
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Runs {@code node --version} and returns the trimmed output, or
     * {@code null} when the command fails or is not found.
     */
    @SuppressWarnings("PMD.DoNotUseThreads")
    private static String detectVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder("node", "--version");
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
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Could not execute 'node --version'", e);
            }
            return null;
        } catch (IOException e) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Could not execute 'node --version'", e);
            }
            return null;
        }
    }

    /**
     * Parses the major version number from a Node.js version string such as
     * {@code "v20.11.0"}.  Returns {@code 0} when the string does not conform
     * to the expected format.
     *
     * @param version Node.js version string
     * @return parsed major version, or {@code 0} on parse failure
     */
    /* default */ static int parseMajorVersion(String version) {
        if (version == null || version.isEmpty()) {
            return 0;
        }
        // Strip leading 'v' then take everything up to the first dot.
        String stripped = version.startsWith("v") ? version.substring(1) : version;
        int dot = stripped.indexOf('.');
        String majorStr = dot >= 0 ? stripped.substring(0, dot) : stripped;
        try {
            return Integer.parseInt(majorStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
