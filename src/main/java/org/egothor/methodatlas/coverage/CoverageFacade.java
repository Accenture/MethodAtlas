// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import java.io.IOException;
import java.nio.file.Path;

import org.egothor.methodatlas.emit.TestMethodSink;

/**
 * Single public entry point used by {@code MethodAtlasApp} to drive
 * {@code -emit-coverage}.
 *
 * <p>
 * The package's data records, collector, writer and mapping loader are all
 * package-private so they remain implementation details. The facade exposes
 * only the operations the CLI needs:
 * </p>
 * <ol>
 *   <li>load a mapping file (returning the framework label and a sink that
 *       must be threaded through the orchestrator);</li>
 *   <li>after the scan, ask the same handle to write the report.</li>
 * </ol>
 */
public final class CoverageFacade {

    /** Default filename used when {@code -coverage-file} is not supplied. */
    public static final String DEFAULT_COVERAGE_FILENAME = "controls-coverage.json";

    private CoverageFacade() {
        // Utility class.
    }

    /**
     * Loads {@code mappingFile} and prepares a coverage collector wrapped
     * inside a {@link Handle} that the CLI can later pass to the scan
     * orchestrator and the writer.
     *
     * @param mappingFile   path to the user-authored mapping JSON
     * @param minConfidence AI minimum-confidence threshold from the CLI
     * @return handle ready for use by the orchestrator and writer
     * @throws IOException              if the mapping file cannot be read
     * @throws IllegalArgumentException if the mapping file is invalid
     */
    public static Handle prepare(Path mappingFile, double minConfidence) throws IOException {
        ControlMapping mapping = ControlMapping.load(mappingFile);
        ControlCoverageCollector collector = new ControlCoverageCollector(mapping, minConfidence);
        return new Handle(collector);
    }

    /**
     * Opaque handle returned by {@link #prepare(Path, double)}. Carries the
     * collector through the scan and yields the report when the scan is done.
     */
    public static final class Handle {

        /** Backing collector — owned by this handle, never exposed directly. */
        private final ControlCoverageCollector collector;

        private Handle(ControlCoverageCollector collector) {
            this.collector = collector;
        }

        /**
         * Returns the {@link TestMethodSink} that the orchestrator must
         * receive every per-method record on.
         *
         * @return collector as a sink
         */
        public TestMethodSink asSink() {
            return collector;
        }

        /**
         * Builds the report and writes it to {@code outputFile}.
         *
         * @param toolVersion resolved tool version string
         * @param outputFile  destination path
         * @throws IOException if the file cannot be written
         */
        public void write(String toolVersion, Path outputFile) throws IOException {
            ControlCoverageReport report = collector.buildReport(toolVersion);
            ControlCoverageWriter.write(report, outputFile);
        }
    }
}
