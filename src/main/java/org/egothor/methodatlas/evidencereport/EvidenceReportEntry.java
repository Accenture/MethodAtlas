// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidencereport;

import java.util.List;

/**
 * A single security-relevant test method captured in an evidence report.
 *
 * <p>
 * Package-private because only {@link EvidenceReportCollector} produces instances
 * and only {@link EvidenceReportWriter} consumes them.
 * </p>
 *
 * @param fqcn               fully qualified class name
 * @param method             test method name
 * @param beginLine          first line of the method in its source file
 * @param loc                lines of code in the method
 * @param contentHash        SHA-256 hash of the enclosing class source, or {@code null}
 * @param sourceTags         {@code @Tag} values declared in source, never {@code null}
 * @param displayName        {@code @DisplayName} annotation value, or {@code null}
 * @param aiDisplayName      AI-suggested display name, or {@code null}
 * @param aiSecurityRelevant AI security-relevance flag
 * @param aiTags             AI taxonomy tags, or {@code null}
 * @param aiReason           AI rationale, or {@code null}
 * @param aiConfidence       AI confidence score, or {@code null}
 * @param aiInteractionScore AI interaction score, or {@code null}
 * @param aiCwe              CWE identifiers derived from AI tags, never {@code null}
 */
/* default */ record EvidenceReportEntry(
        String fqcn,
        String method,
        int beginLine,
        int loc,
        String contentHash,
        List<String> sourceTags,
        String displayName,
        String aiDisplayName,
        Boolean aiSecurityRelevant,
        List<String> aiTags,
        String aiReason,
        Double aiConfidence,
        Double aiInteractionScore,
        List<String> aiCwe) {
}
