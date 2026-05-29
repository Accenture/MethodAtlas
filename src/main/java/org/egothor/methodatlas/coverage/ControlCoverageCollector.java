// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.emit.TestMethodSink;

/**
 * Streaming sink that builds a {@link ControlCoverageReport} from scan
 * records observed during a single run.
 *
 * <p>
 * The collector implements {@link TestMethodSink} so the orchestration layer
 * can hand it the same record stream that drives SARIF/CSV emitters, without
 * a second scan and without coupling the emitters to compliance logic.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code coverage} package needs
 * to construct one; {@link CoverageFacade} is the sole external entry point.
 * </p>
 */
final class ControlCoverageCollector implements TestMethodSink {

    /** Schema version of the produced report. */
    private static final String SCHEMA_VERSION = "1";

    /** Separator between framework prefix and bare ID in the control key. */
    private static final String CONTROL_ID_PREFIX_SEP = "-";

    /** Tag-source label when only source annotations contribute. */
    private static final String TAG_SOURCE_ANNOTATION = "source";

    /** Tag-source label when only AI classification contributes. */
    private static final String TAG_SOURCE_AI = "ai";

    /** Tag-source label when both source annotations and AI contribute. */
    private static final String TAG_SOURCE_BOTH = "both";

    /** Multiplier used to round percentages to two decimal places. */
    private static final double COVERAGE_PERCENT_SCALE = 10_000.0;

    /** Divisor that converts the rounded ratio back into a percentage. */
    private static final double COVERAGE_PERCENT_DIVISOR = 100.0;

    /** Confidence assigned to source-derived evidence (always certain). */
    private static final double FULL_CONFIDENCE = 1.0;

    /** Mapping that drives tag → control lookup. */
    private final ControlMapping mapping;

    /** Minimum AI confidence required to count an AI-only classification. */
    private final double minConfidence;

    /**
     * Accumulated covering tests, keyed by canonical control key
     * (e.g. {@code "ASVS-4.1.1"}). Insertion order is unimportant here — the
     * report-build step sorts the resulting set lexicographically.
     */
    private final Map<String, List<CoverageTestEntry>> accumulator = new LinkedHashMap<>();

    /**
     * Creates a collector backed by {@code mapping}.
     *
     * @param mapping       loaded control mapping; must not be {@code null}
     * @param minConfidence minimum AI confidence required for an AI-only
     *                      classification to count; source-derived evidence
     *                      is always counted irrespective of this value
     */
    /* default */ ControlCoverageCollector(ControlMapping mapping, double minConfidence) {
        this.mapping = mapping;
        this.minConfidence = minConfidence;
    }

    /**
     * Records evidence contributed by a single test method.
     *
     * <p>
     * Resolution algorithm:
     * </p>
     * <ol>
     *   <li>Filter the source {@code tags} list to only tags that appear in
     *       the mapping (others are silently skipped — not an error).</li>
     *   <li>Filter the AI {@link AiMethodSuggestion#tags()} the same way, but
     *       only when the suggestion is non-null, security-relevant, and meets
     *       the configured minimum confidence threshold. {@code null} AI tag
     *       lists are treated as empty.</li>
     *   <li>Determine {@code tagSource} and {@code confidence}: source-only
     *       and AI+source both yield certainty ({@code 1.0}); AI-only carries
     *       the AI confidence forward verbatim.</li>
     *   <li>Merge the two tag lists preserving first-seen order, drop the
     *       method when the merged list is empty, and otherwise append a
     *       {@link CoverageTestEntry} under every linked control key.</li>
     * </ol>
     *
     * @param fqcn        fully qualified class name
     * @param method      test method name
     * @param beginLine   ignored
     * @param loc         ignored
     * @param contentHash ignored
     * @param tags        source-declared tags
     * @param displayName optional human-readable display name
     * @param suggestion  optional AI classification
     */
    @Override
    @SuppressWarnings({"PMD.UseObjectForClearerAPI", "PMD.AvoidInstantiatingObjectsInLoops"})
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion) {
        List<String> sourceMappable = filterMappable(tags);
        List<String> aiMappable = filterAiMappable(suggestion);
        if (sourceMappable.isEmpty() && aiMappable.isEmpty()) {
            return;
        }

        String tagSource;
        double confidence;
        if (sourceMappable.isEmpty()) {
            tagSource = TAG_SOURCE_AI;
            confidence = suggestion.confidence();
        } else if (aiMappable.isEmpty()) {
            tagSource = TAG_SOURCE_ANNOTATION;
            confidence = FULL_CONFIDENCE;
        } else {
            tagSource = TAG_SOURCE_BOTH;
            confidence = FULL_CONFIDENCE;
        }

        List<String> merged = mergeUnique(sourceMappable, aiMappable);
        CoverageTestEntry entry = new CoverageTestEntry(
                fqcn, method, displayName, merged, tagSource, confidence);

        for (String tag : merged) {
            List<ControlEntry> controls = mapping.tagToControls().get(tag);
            for (ControlEntry control : controls) {
                String key = controlKey(control.id());
                accumulator.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            }
        }
    }

    /**
     * Builds the final {@link ControlCoverageReport}.
     *
     * @param toolVersion resolved tool version string; never {@code null}
     * @return populated report with sorted coverage, gaps, and statistics
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    /* default */ ControlCoverageReport buildReport(String toolVersion) {
        NavigableSet<String> allKeys = new TreeSet<>();
        Map<String, ControlEntry> firstControlByKey = new LinkedHashMap<>();
        mapping.tagToControls().forEach((tag, entries) -> entries.forEach(control -> {
            String key = controlKey(control.id());
            allKeys.add(key);
            firstControlByKey.putIfAbsent(key, control);
        }));

        Map<String, CoverageControlEntry> coverage = new LinkedHashMap<>();
        for (String key : allKeys) {
            List<CoverageTestEntry> tests = accumulator.get(key);
            if (tests == null || tests.isEmpty()) {
                continue;
            }
            ControlEntry metadata = firstControlByKey.get(key);
            String chapter = chapterFor(key, metadata);
            String chapterTitle = chapterTitleFor(key, metadata);
            coverage.put(key, new CoverageControlEntry(chapter, chapterTitle,
                    Collections.unmodifiableList(new ArrayList<>(tests))));
        }

        List<String> gaps = new ArrayList<>();
        for (String key : allKeys) {
            if (!coverage.containsKey(key)) {
                gaps.add(key);
            }
        }

        CoverageStatistics statistics = buildStatistics(allKeys.size(), coverage.size());
        return new ControlCoverageReport(
                SCHEMA_VERSION,
                Instant.now().toString(),
                toolVersion,
                mapping.framework(),
                mapping.frameworkVersion(),
                mapping.source(),
                Collections.unmodifiableMap(coverage),
                Collections.unmodifiableList(gaps),
                statistics);
    }

    /**
     * Picks the first non-null {@code chapter} value attached to the control
     * across every mapping tag that points at it.
     *
     * @param key      canonical control key (e.g. {@code "ASVS-4.1.1"})
     * @param fallback control entry used when no tag-keyed value is set
     * @return chapter label or {@code null}
     */
    private String chapterFor(String key, ControlEntry fallback) {
        for (List<ControlEntry> entries : mapping.tagToControls().values()) {
            for (ControlEntry candidate : entries) {
                if (controlKey(candidate.id()).equals(key) && candidate.chapter() != null) {
                    return candidate.chapter();
                }
            }
        }
        return fallback.chapter();
    }

    /**
     * Picks the first non-null {@code chapterTitle} for the control. Same
     * resolution policy as {@link #chapterFor(String, ControlEntry)}.
     *
     * @param key      canonical control key
     * @param fallback control entry used when no tag-keyed value is set
     * @return chapter title or {@code null}
     */
    private String chapterTitleFor(String key, ControlEntry fallback) {
        for (List<ControlEntry> entries : mapping.tagToControls().values()) {
            for (ControlEntry candidate : entries) {
                if (controlKey(candidate.id()).equals(key) && candidate.chapterTitle() != null) {
                    return candidate.chapterTitle();
                }
            }
        }
        return fallback.chapterTitle();
    }

    /**
     * Computes aggregate counts and the rounded coverage percentage.
     *
     * @param total   total distinct control IDs declared in the mapping
     * @param covered count of controls with at least one covering test
     * @return populated statistics record
     */
    private static CoverageStatistics buildStatistics(int total, int covered) {
        double ratio = total == 0 ? 0.0 : covered / (double) total;
        double percent = Math.round(ratio * COVERAGE_PERCENT_SCALE) / COVERAGE_PERCENT_DIVISOR;
        return new CoverageStatistics(total, covered, total - covered, percent);
    }

    /**
     * Returns the canonical {@code <FRAMEWORK>-<id>} key used throughout the
     * report. The framework prefix is upper-cased so {@code "asvs"} and
     * {@code "ASVS"} mapping files produce identical output.
     *
     * @param id bare requirement ID from a {@link ControlEntry}
     * @return canonical control key
     */
    private String controlKey(String id) {
        return mapping.framework().toUpperCase(Locale.ROOT) + CONTROL_ID_PREFIX_SEP + id;
    }

    /**
     * Drops every tag that is not present in {@link ControlMapping#tagToControls()}.
     *
     * @param tags raw tag list; may be {@code null}
     * @return new list containing only mappable tags; never {@code null}
     */
    private List<String> filterMappable(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(tags.size());
        for (String tag : tags) {
            if (mapping.tagToControls().containsKey(tag)) {
                result.add(tag);
            }
        }
        return result;
    }

    /**
     * Extracts the mappable subset of an AI suggestion's tags, applying the
     * security-relevance and minimum-confidence filters.
     *
     * @param suggestion AI suggestion; may be {@code null}
     * @return new list containing only mappable AI tags; never {@code null}
     */
    private List<String> filterAiMappable(AiMethodSuggestion suggestion) {
        if (suggestion == null || !suggestion.securityRelevant()) {
            return List.of();
        }
        if (suggestion.confidence() < minConfidence) {
            return List.of();
        }
        return filterMappable(suggestion.tags());
    }

    /**
     * Returns an unmodifiable union of two lists preserving first-seen order.
     *
     * @param first  first list (typically source tags)
     * @param second second list (typically AI tags)
     * @return unmodifiable union with stable order
     */
    private static List<String> mergeUnique(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }
}
