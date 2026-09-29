// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidencereport;

import java.util.List;
import java.util.Map;

/**
 * Top-level evidence report passed from {@link EvidenceReportCollector} to
 * {@link EvidenceReportWriter}.
 *
 * <p>
 * Package-private because nothing outside this package needs to construct or
 * inspect the report directly; the Markdown form is the contract.
 * </p>
 *
 * @param generatedUtc           ISO-8601 instant at report-creation time
 * @param toolVersion            tool version string, or {@code "dev"}
 * @param scanRoots              scan-root paths as string representations
 * @param totalScanned           total number of test methods observed by the collector
 * @param securityRelevantCount  number of security-relevant methods included in the report
 * @param byClass                insertion-ordered map of FQCN → entries for that class
 */
/* default */ record EvidenceReport(
        String generatedUtc,
        String toolVersion,
        List<String> scanRoots,
        int totalScanned,
        int securityRelevantCount,
        Map<String, List<EvidenceReportEntry>> byClass) {
}
