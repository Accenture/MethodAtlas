// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.attest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.egothor.methodatlas.emit.TestMethodSink;

/**
 * Single public entry point used by {@code MethodAtlasApp} to drive
 * {@code -attest}.
 *
 * <p>
 * The package's data records, collector, and writer are all package-private so
 * they remain implementation details. The facade exposes only the operations
 * the CLI needs:
 * </p>
 * <ol>
 *   <li>prepare a collector (returning a handle that must be threaded through
 *       the orchestrator as an extra sink);</li>
 *   <li>after the scan, ask the same handle to write the attestation
 *       manifest.</li>
 * </ol>
 *
 * <p>
 * The manifest is a compact JSON file listing every AI-classified
 * security-relevant test method, the commit SHA (resolved from CI environment
 * variables), and optional content hashes. It is designed to be signed
 * externally with {@code cosign sign-blob} or GPG and attached to a release as
 * a tamper-evident record of which security tests existed at that point.
 * </p>
 */
public final class AttestFacade {

    /**
     * Default filename used when {@code -attest-file} is not supplied.
     */
    public static final String DEFAULT_ATTEST_FILENAME = "security-tests.attestation.json";

    private AttestFacade() {
        // Utility class.
    }

    /**
     * Prepares an attestation collector.
     *
     * @param minConfidence AI minimum-confidence threshold; AI-only evidence below
     *                      this value is excluded from the manifest
     * @param scanRoots     scan root paths written to the manifest header
     * @return handle ready for use by the orchestrator and writer
     */
    public static Handle prepare(double minConfidence, List<Path> scanRoots) {
        List<String> rootStrings = scanRoots.stream()
                .map(Path::toString)
                .collect(Collectors.toUnmodifiableList());
        AttestCollector collector = new AttestCollector(minConfidence, rootStrings);
        return new Handle(collector);
    }

    /**
     * Opaque handle returned by {@link #prepare(double, List)}. Carries the
     * collector through the scan and yields the manifest when the scan is done.
     */
    public static final class Handle {

        /** Backing collector — owned by this handle, never exposed directly. */
        private final AttestCollector collector;

        private Handle(AttestCollector collector) {
            this.collector = collector;
        }

        /**
         * Returns the {@link TestMethodSink} that the orchestrator must receive
         * every per-method record on.
         *
         * @return collector as a sink
         */
        public TestMethodSink asSink() {
            return collector;
        }

        /**
         * Builds the attestation manifest and writes it to {@code outputFile}.
         *
         * @param toolVersion resolved tool version string
         * @param outputFile  destination path
         * @throws IOException if the file cannot be written
         */
        public void write(String toolVersion, Path outputFile) throws IOException {
            AttestReport report = collector.buildReport(toolVersion);
            AttestWriter.write(report, outputFile);
        }
    }
}
