// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidencereport;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialises an {@link EvidenceReport} to disk as a Markdown document.
 *
 * <p>
 * Package-private because nothing outside the {@code evidencereport} package needs
 * direct access; {@link EvidenceReportFacade} is the sole external caller.
 * </p>
 */
final class EvidenceReportWriter {

    private EvidenceReportWriter() {
        // Utility class.
    }

    /**
     * Writes {@code report} to {@code outputFile} as UTF-8 Markdown.
     *
     * @param report     the evidence report to serialise
     * @param outputFile destination path; parent directory must exist
     * @throws IOException if the file cannot be written
     */
    /* default */ static void write(EvidenceReport report, Path outputFile) throws IOException {
        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8))) {
            writeHeader(out, report);
            writeSummary(out, report);
            writeMethods(out, report);
        }
    }

    private static void writeHeader(PrintWriter out, EvidenceReport report) {
        out.println("# MethodAtlas Security Evidence Report");
        out.println();
        out.println("**Generated:** " + report.generatedUtc() + "  ");
        out.println("**Tool version:** " + report.toolVersion() + "  ");
        if (!report.scanRoots().isEmpty()) {
            out.print("**Scan roots:** ");
            StringBuilder sb = new StringBuilder();
            for (String root : report.scanRoots()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append('`').append(root).append('`');
            }
            out.println(sb + "  ");
        }
        out.println("**Total methods scanned:** " + report.totalScanned() + "  ");
        out.println("**Security-relevant methods:** " + report.securityRelevantCount() + "  ");
        out.println();
    }

    private static void writeSummary(PrintWriter out, EvidenceReport report) {
        out.println("## Summary");
        out.println();
        Map<String, Integer> domainCounts = new LinkedHashMap<>();
        for (List<EvidenceReportEntry> entries : report.byClass().values()) {
            for (EvidenceReportEntry entry : entries) {
                if (entry.aiTags() != null) {
                    for (String tag : entry.aiTags()) {
                        domainCounts.merge(tag, 1, Integer::sum);
                    }
                }
            }
        }
        if (domainCounts.isEmpty()) {
            out.println("No security-relevant methods were found in this scan.");
        } else {
            out.println("| Domain | Count |");
            out.println("|--------|-------|");
            for (Map.Entry<String, Integer> e : domainCounts.entrySet()) {
                out.println("| " + e.getKey() + " | " + e.getValue() + " |");
            }
        }
        out.println();
    }

    private static void writeMethods(PrintWriter out, EvidenceReport report) {
        if (report.byClass().isEmpty()) {
            return;
        }
        out.println("## Security Test Methods");
        out.println();
        for (Map.Entry<String, List<EvidenceReportEntry>> classEntry : report.byClass().entrySet()) {
            out.println("### " + classEntry.getKey());
            out.println();
            for (EvidenceReportEntry entry : classEntry.getValue()) {
                writeEntry(out, entry);
            }
        }
    }

    @SuppressWarnings("PMD.NPathComplexity")
    private static void writeEntry(PrintWriter out, EvidenceReportEntry entry) {
        out.println("#### " + entry.method());
        out.println();
        out.println("| Field | Value |");
        out.println("|-------|-------|");
        String dispName = entry.aiDisplayName() != null ? entry.aiDisplayName()
                : entry.displayName() != null ? entry.displayName() : "";
        if (!dispName.isEmpty()) {
            out.println("| Display name | " + mdEscape(dispName) + " |");
        }
        if (!entry.sourceTags().isEmpty()) {
            out.println("| Source tags | " + String.join(", ", entry.sourceTags()) + " |");
        }
        if (entry.aiTags() != null && !entry.aiTags().isEmpty()) {
            out.println("| AI tags | " + String.join(", ", entry.aiTags()) + " |");
        }
        if (entry.aiSecurityRelevant() != null) {
            out.println("| AI security relevant | " + entry.aiSecurityRelevant() + " |");
        }
        if (entry.aiConfidence() != null) {
            out.println("| Confidence | " + entry.aiConfidence() + " |");
        }
        if (entry.aiInteractionScore() != null) {
            out.println("| Interaction score | " + entry.aiInteractionScore() + " |");
        }
        if (entry.contentHash() != null) {
            out.println("| Content hash | `" + entry.contentHash() + "` |");
        }
        if (entry.aiReason() != null && !entry.aiReason().isBlank()) {
            out.println("| Reason | " + mdEscape(entry.aiReason()) + " |");
        }
        if (!entry.aiCwe().isEmpty()) {
            out.println("| CWE | " + String.join(", ", entry.aiCwe()) + " |");
        }
        if (entry.beginLine() > 0) {
            out.println("| Line | " + entry.beginLine() + " |");
        }
        out.println();
    }

    /** Escapes pipe characters so they do not break Markdown table cells. */
    private static String mdEscape(String value) {
        return value.replace("|", "\\|");
    }
}
