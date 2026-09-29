// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidencereport;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.CweMapping;
import org.egothor.methodatlas.emit.TestMethodSink;

/**
 * Streaming sink that builds an {@link EvidenceReport} from scan records observed during
 * a single run.
 *
 * <p>
 * The collector implements {@link TestMethodSink} so the orchestration layer can
 * hand it the same record stream that drives SARIF/CSV emitters, without a second
 * scan and without coupling the emitters to evidence-report logic.
 * </p>
 *
 * <p>
 * Only AI-classified, security-relevant methods whose confidence meets the configured
 * minimum are collected. Methods without an AI suggestion, or whose suggestion is not
 * security-relevant, are silently skipped.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code evidencereport} package needs to
 * construct one; {@link EvidenceReportFacade} is the sole external entry point.
 * </p>
 */
final class EvidenceReportCollector implements TestMethodSink {

    /** Minimum AI confidence required to include a method in the report. */
    private final double minConfidence;

    /** Scan-root paths forwarded to the report header; stored as strings for serialisation. */
    private final List<String> scanRoots;

    /** Total methods seen (all, not just security-relevant). */
    private int totalScanned;

    /**
     * Accumulated entries grouped by FQCN in encounter order. Each FQCN maps to
     * the ordered list of qualifying methods in that class.
     */
    private final Map<String, List<EvidenceReportEntry>> accumulator = new LinkedHashMap<>();

    /**
     * Creates a collector.
     *
     * @param minConfidence minimum AI confidence required to include a method;
     *                      must be in {@code [0.0, 1.0]}
     * @param scanRoots     scan-root paths included verbatim in the report header
     */
    /* default */ EvidenceReportCollector(double minConfidence, List<Path> scanRoots) {
        this.minConfidence = minConfidence;
        List<String> roots = new ArrayList<>(scanRoots.size());
        for (Path p : scanRoots) {
            roots.add(p.toString());
        }
        this.scanRoots = Collections.unmodifiableList(roots);
    }

    /**
     * Records a single test method.
     *
     * <p>
     * Every call increments the total-scanned counter. Only methods with a non-null,
     * security-relevant AI suggestion meeting the minimum confidence threshold are
     * added to the evidence set.
     * </p>
     *
     * @param fqcn        fully qualified class name
     * @param method      test method name
     * @param beginLine   first source line of the method
     * @param loc         lines of code
     * @param contentHash SHA-256 of the enclosing class source, or {@code null}
     * @param tags        source-declared {@code @Tag} values
     * @param displayName {@code @DisplayName} annotation value, or {@code null}
     * @param suggestion  AI classification, or {@code null}
     */
    @Override
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion) {
        totalScanned++;
        if (suggestion == null || !suggestion.securityRelevant()) {
            return;
        }
        if (suggestion.confidence() < minConfidence) {
            return;
        }
        List<String> aiTags = suggestion.tags() != null
                ? Collections.unmodifiableList(new ArrayList<>(suggestion.tags()))
                : List.of();
        List<String> cwe = CweMapping.forTags(aiTags);
        EvidenceReportEntry entry = new EvidenceReportEntry(
                fqcn, method, beginLine, loc, contentHash,
                tags != null ? Collections.unmodifiableList(new ArrayList<>(tags)) : List.of(),
                displayName,
                suggestion.displayName(),
                suggestion.securityRelevant(),
                aiTags,
                suggestion.reason(),
                suggestion.confidence(),
                suggestion.interactionScore(),
                cwe);
        accumulator.computeIfAbsent(fqcn, k -> new ArrayList<>()).add(entry);
    }

    /**
     * Builds the final {@link EvidenceReport}.
     *
     * @param toolVersion resolved tool version string; never {@code null}
     * @return populated report
     */
    /* default */ EvidenceReport buildReport(String toolVersion) {
        int secCount = accumulator.values().stream().mapToInt(List::size).sum();
        Map<String, List<EvidenceReportEntry>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<EvidenceReportEntry>> e : accumulator.entrySet()) {
            snapshot.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue()))); // NOPMD AvoidInstantiatingObjectsInLoops
        }
        return new EvidenceReport(
                Instant.now().toString(),
                toolVersion,
                scanRoots,
                totalScanned,
                secCount,
                Collections.unmodifiableMap(snapshot));
    }
}
