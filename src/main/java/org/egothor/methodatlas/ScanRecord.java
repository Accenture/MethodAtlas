package org.egothor.methodatlas;

import java.util.List;

/**
 * A single row from a MethodAtlas CSV output file.
 *
 * <p>
 * Instances are created by {@link DeltaReport} when parsing CSV files for
 * delta comparison. Each instance corresponds to one test method row in the
 * output.
 * </p>
 *
 * <p>
 * Fields that correspond to optional output columns are {@code null} when the
 * column was absent from the source file. This happens when the producing scan
 * was run without the corresponding flag (e.g. no {@code -content-hash}, no
 * {@code -ai}). Delta comparison skips any field that is {@code null} in either
 * record so that scans produced with different flag sets can still be compared
 * on the fields they share.
 * </p>
 *
 * @param fqcn               fully qualified class name; always present
 * @param method             test method name; always present
 * @param loc                lines of code for the method declaration; always present
 * @param tags               JUnit {@code @Tag} values on the method; empty list
 *                           when none; never {@code null}
 * @param contentHash        SHA-256 fingerprint of the enclosing class source, or
 *                           {@code null} when the {@code content_hash} column was
 *                           absent (scan run without {@code -content-hash})
 * @param aiSecurityRelevant AI security-relevance classification, or {@code null}
 *                           when the {@code ai_security_relevant} column was absent
 *                           (scan run without {@code -ai})
 * @param aiDisplayName      AI-suggested {@code @DisplayName} value, or {@code null}
 *                           when absent
 * @param aiTags             AI-assigned taxonomy tags, or {@code null} when the
 *                           {@code ai_tags} column was absent; empty list when the
 *                           column was present but empty
 * @param aiReason           AI rationale for the classification, or {@code null}
 *                           when absent
 * @param aiConfidence       AI confidence score ({@code 0.0–1.0}), or {@code null}
 *                           when the {@code ai_confidence} column was absent
 * @param aiInteractionScore AI interaction score ({@code 0.0–1.0}), or {@code null}
 *                           when the {@code ai_interaction_score} column was absent
 * @param tagAiDrift         tag-vs-AI drift value ({@code none}, {@code tag-only},
 *                           or {@code ai-only}), or {@code null} when the
 *                           {@code tag_ai_drift} column was absent
 *
 * @see DeltaReport
 * @see DeltaEntry
 */
record ScanRecord(
        String fqcn,
        String method,
        int loc,
        List<String> tags,
        String contentHash,
        Boolean aiSecurityRelevant,
        String aiDisplayName,
        List<String> aiTags,
        String aiReason,
        Double aiConfidence,
        Double aiInteractionScore,
        String tagAiDrift) {
}
