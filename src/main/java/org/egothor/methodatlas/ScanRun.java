// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record identifying one CLI invocation of MethodAtlas.
 *
 * <p>
 * Created once at the top of {@link MethodAtlasApp#run(String[], java.io.PrintWriter)}
 * and propagated through {@link ScanRunContext} so that every log record
 * emitted during the run can carry the correlation id. This makes it
 * possible to trace a single audit-trail artefact, a single SARIF file, or a
 * single CI log slice back to the exact run that produced it.
 * </p>
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@code runId} — a short hex-encoded random id (16 hex characters);
 *       intentionally not a full UUID because the id appears in log lines
 *       and CSV preamble where 36-character UUIDs are noisy.</li>
 *   <li>{@code startedAt} — wall-clock timestamp at run construction.</li>
 *   <li>{@code toolVersion} — implementation version reported by the
 *       {@code methodatlas} JAR manifest; falls back to {@code "dev"} for
 *       developer builds.</li>
 *   <li>{@code configFingerprint} — SHA-256 of a stable textual rendering of
 *       the parsed {@link CliConfig}. Lets two runs of the same configuration
 *       be correlated across time and machine boundaries.</li>
 * </ul>
 *
 * @param runId             short hex correlation id, non-empty
 * @param startedAt         wall-clock time the run was started
 * @param toolVersion       implementation version or {@code "dev"}
 * @param configFingerprint SHA-256 hex digest of the canonical configuration
 *                          text; never {@code null}, never empty
 * @since 1.0.0
 */
public record ScanRun(String runId, Instant startedAt, String toolVersion, String configFingerprint) {

    /**
     * Compact constructor enforcing the documented invariants on the
     * component values.
     */
    public ScanRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(toolVersion, "toolVersion");
        Objects.requireNonNull(configFingerprint, "configFingerprint");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (configFingerprint.isBlank()) {
            throw new IllegalArgumentException("configFingerprint must not be blank");
        }
    }

    /**
     * Creates a new {@code ScanRun} with a freshly generated correlation id
     * and the current wall-clock timestamp.
     *
     * <p>
     * The {@code configFingerprint} is the SHA-256 of {@code configText}, a
     * stable textual rendering of the parsed CLI configuration. Two runs
     * with byte-identical configuration produce the same fingerprint,
     * letting operators correlate scans without sharing the full config.
     * </p>
     *
     * @param toolVersion implementation version, or {@code "dev"} for
     *                    builds without a manifest version
     * @param configText  canonical textual rendering of the parsed
     *                    {@link CliConfig}; must not be {@code null}
     * @return a fresh scan-run identifier
     */
    public static ScanRun create(String toolVersion, String configText) {
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new ScanRun(runId, Instant.now(),
                toolVersion == null || toolVersion.isBlank() ? "dev" : toolVersion,
                sha256(configText));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value == null ? new byte[0] : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
