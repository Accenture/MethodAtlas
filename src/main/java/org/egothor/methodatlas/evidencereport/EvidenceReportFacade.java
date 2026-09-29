// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidencereport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.egothor.methodatlas.emit.TestMethodSink;

/**
 * Single public entry point used by {@code MethodAtlasApp} to drive {@code -evidence-report}.
 *
 * <p>
 * The package's collector, report records, and writer are all package-private so they remain
 * implementation details. The facade exposes only the operations the CLI needs:
 * </p>
 * <ol>
 *   <li>prepare a collector (returning a handle that must be threaded through the orchestrator
 *       as an extra sink);</li>
 *   <li>after the scan, ask the same handle to write the Markdown evidence report.</li>
 * </ol>
 */
public final class EvidenceReportFacade {

    /** Default filename used when {@code -evidence-report-file} is not supplied. */
    public static final String DEFAULT_REPORT_FILENAME = "security-evidence-report.md";

    private EvidenceReportFacade() {
        // Utility class.
    }

    /**
     * Prepares an evidence-report collector.
     *
     * @param minConfidence AI minimum-confidence threshold; AI-only evidence below this
     *                      value is excluded from the report
     * @param scanRoots     paths passed to the scan; included verbatim in the report header
     * @return handle ready for use by the orchestrator and writer
     */
    public static Handle prepare(double minConfidence, List<Path> scanRoots) {
        EvidenceReportCollector collector = new EvidenceReportCollector(minConfidence, scanRoots);
        return new Handle(collector);
    }

    /**
     * Opaque handle returned by {@link #prepare(double, List)}. Carries the collector through
     * the scan and yields the Markdown report when the scan is done.
     */
    public static final class Handle {

        /** Backing collector — owned by this handle, never exposed directly. */
        private final EvidenceReportCollector collector;

        private Handle(EvidenceReportCollector collector) {
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
         * Builds the evidence report and writes it to {@code outputFile} as Markdown.
         *
         * @param toolVersion resolved tool version string
         * @param outputFile  destination path
         * @throws IOException if the file cannot be written
         */
        public void write(String toolVersion, Path outputFile) throws IOException {
            EvidenceReport report = collector.buildReport(toolVersion);
            EvidenceReportWriter.write(report, outputFile);
        }
    }
}
