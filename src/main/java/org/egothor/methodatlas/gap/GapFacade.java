// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gap;

import java.io.IOException;
import java.nio.file.Path;

import org.egothor.methodatlas.emit.TestMethodSink;

/**
 * Single public entry point used by {@code MethodAtlasApp} to drive {@code -gap-report}.
 *
 * <p>
 * The package's data records, collector, and writer are all package-private so they
 * remain implementation details. The facade exposes only the operations the CLI needs:
 * </p>
 * <ol>
 *   <li>prepare a collector (returning a handle that must be threaded through the
 *       orchestrator as an extra sink);</li>
 *   <li>after the scan, ask the same handle to write the report.</li>
 * </ol>
 *
 * @since 3.0.0
 */
public final class GapFacade {

    /** Default filename used when {@code -gap-report-file} is not supplied. */
    public static final String DEFAULT_GAP_REPORT_FILENAME = "security-gap-report.json";

    private GapFacade() {
        // Utility class.
    }

    /**
     * Prepares a gap-report collector.
     *
     * @param minConfidence AI minimum-confidence threshold; AI-only evidence below this
     *                      value is excluded from the gap report
     * @return handle ready for use by the orchestrator and writer
     * @since 3.0.0
     */
    public static Handle prepare(double minConfidence) {
        GapCollector collector = new GapCollector(minConfidence);
        return new Handle(collector);
    }

    /**
     * Opaque handle returned by {@link #prepare(double)}. Carries the collector
     * through the scan and yields the report when the scan is done.
     */
    public static final class Handle {

        /** Backing collector — owned by this handle, never exposed directly. */
        private final GapCollector collector;

        private Handle(GapCollector collector) {
            this.collector = collector;
        }

        /**
         * Returns the {@link TestMethodSink} that the orchestrator must receive every
         * per-method record on.
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
            GapReport report = collector.buildReport(toolVersion);
            GapReportWriter.write(report, outputFile);
        }
    }
}
