// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.emit.TestMethodSink;

/**
 * Streaming sink that builds a {@link GapReport} from scan records observed during
 * a single run.
 *
 * <p>
 * The collector implements {@link TestMethodSink} so the orchestration layer can
 * hand it the same record stream that drives SARIF/CSV emitters, without a second
 * scan and without coupling the emitters to gap-analysis logic.
 * </p>
 *
 * <p>
 * Only AI-classified, security-relevant methods are counted. Methods without an
 * AI suggestion, or whose suggestion is not security-relevant, or whose confidence
 * falls below the configured threshold, are silently skipped.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code gap} package needs to
 * construct one; {@link GapFacade} is the sole external entry point.
 * </p>
 */
final class GapCollector implements TestMethodSink {

    /** Schema version of the produced report. */
    private static final String SCHEMA_VERSION = "1";

    /** Multiplier used to round percentages to two decimal places. */
    private static final double COVERAGE_PERCENT_SCALE = 10_000.0;

    /** Divisor that converts the rounded ratio back into a percentage. */
    private static final double COVERAGE_PERCENT_DIVISOR = 100.0;

    /**
     * Canonical ordered set of the nine built-in taxonomy tags, mirroring the
     * order in {@code DefaultSecurityTaxonomy}.
     */
    private static final List<String> TAXONOMY_TAGS = List.of(
            "auth", "access-control", "crypto", "input-validation",
            "injection", "data-protection", "logging", "error-handling", "owasp");

    /** Fast lookup set for {@link #TAXONOMY_TAGS}. */
    private static final Set<String> TAXONOMY_TAG_SET = Set.copyOf(TAXONOMY_TAGS);

    /** Minimum AI confidence required to count a classification. */
    private final double minConfidence;

    /**
     * Accumulated methods per taxonomy tag. Initialised with all nine tags so
     * every tag appears in the report even when no method covers it.
     */
    private final Map<String, List<GapReportMethod>> accumulator;

    /**
     * Creates a collector that counts AI-classified security tests per taxonomy domain.
     *
     * @param minConfidence minimum AI confidence required for a classification to
     *                      be counted; must be in {@code [0.0, 1.0]}
     */
    /* default */ GapCollector(double minConfidence) {
        this.minConfidence = minConfidence;
        Map<String, List<GapReportMethod>> acc = new LinkedHashMap<>();
        for (String tag : TAXONOMY_TAGS) {
            acc.put(tag, new ArrayList<>()); // NOPMD AvoidInstantiatingObjectsInLoops
        }
        this.accumulator = acc;
    }

    /**
     * Records evidence contributed by a single test method.
     *
     * <p>
     * Only methods whose AI suggestion is non-null, security-relevant, and meets
     * the minimum confidence threshold are counted. For each qualifying AI tag that
     * belongs to the built-in taxonomy, the method is recorded under that domain.
     * </p>
     *
     * @param fqcn        fully qualified class name
     * @param method      test method name
     * @param beginLine   ignored
     * @param loc         ignored
     * @param contentHash ignored
     * @param tags        ignored (gap report relies on AI classification only)
     * @param displayName optional human-readable display name
     * @param suggestion  optional AI classification
     */
    @Override
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion) {
        if (suggestion == null || !suggestion.securityRelevant()) {
            return;
        }
        if (suggestion.confidence() < minConfidence) {
            return;
        }
        List<String> aiTags = suggestion.tags();
        if (aiTags == null || aiTags.isEmpty()) {
            return;
        }
        GapReportMethod entry = new GapReportMethod(fqcn, method, displayName);
        for (String tag : aiTags) {
            if (TAXONOMY_TAG_SET.contains(tag)) {
                accumulator.get(tag).add(entry);
            }
        }
    }

    /**
     * Builds the final {@link GapReport}.
     *
     * @param toolVersion resolved tool version string; never {@code null}
     * @return populated report with per-domain coverage, gap list, and summary
     */
    /* default */ GapReport buildReport(String toolVersion) {
        Map<String, GapReportDomain> domains = new LinkedHashMap<>();
        List<String> gaps = new ArrayList<>();
        int covered = 0;
        for (String tag : TAXONOMY_TAGS) {
            List<GapReportMethod> methods = accumulator.get(tag);
            List<GapReportMethod> copy = new ArrayList<>(methods); // NOPMD AvoidInstantiatingObjectsInLoops
            domains.put(tag, new GapReportDomain(methods.size(), // NOPMD AvoidInstantiatingObjectsInLoops
                    Collections.unmodifiableList(copy)));
            if (methods.isEmpty()) {
                gaps.add(tag);
            } else {
                covered++;
            }
        }
        int total = TAXONOMY_TAGS.size();
        double percent = total == 0 ? 0.0
                : Math.round(covered / (double) total * COVERAGE_PERCENT_SCALE) / COVERAGE_PERCENT_DIVISOR;
        GapReportSummary summary = new GapReportSummary(total, covered, total - covered, percent);
        return new GapReport(
                SCHEMA_VERSION,
                Instant.now().toString(),
                toolVersion,
                Collections.unmodifiableMap(domains),
                Collections.unmodifiableList(gaps),
                summary);
    }
}
