// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure, deterministic computation of AI-classification quality metrics from
 * (prediction, truth) pairs.
 *
 * <p>
 * This class performs no I/O and calls no AI provider: given the same
 * {@link Sample} list it always returns the same {@link Result}, so the metric
 * math is unit-testable offline. Reading labels and predictions from files is
 * the job of {@link AccuracyEvaluator}.
 * </p>
 *
 * <h2>Two evaluated dimensions</h2>
 *
 * <ul>
 * <li><b>{@code securityRelevant}</b> — a binary decision per method, scored as a
 *     confusion matrix (TP/FP/FN/TN) where "positive" means security-relevant.
 *     Only samples whose truth label carries a relevance decision take part.</li>
 * <li><b>taxonomy tags</b> — a multi-label decision per method, scored
 *     one-vs-rest per tag (a tag is a TP when predicted and labelled, an FP when
 *     predicted only, an FN when labelled only) plus micro- and macro-averages.
 *     Only samples whose truth label carries a tag set take part.</li>
 * </ul>
 *
 * <h2>Undefined values</h2>
 *
 * <p>
 * Precision is {@code TP/(TP+FP)}, recall is {@code TP/(TP+FN)}, F1 is
 * {@code 2TP/(2TP+FP+FN)} and accuracy is {@code (TP+TN)/total}. When a
 * denominator is zero the metric is mathematically undefined, and it is reported
 * as {@link OptionalDouble#empty()} rather than silently as {@code 0} or
 * {@code 1}: "no evidence" must not read as "perfect" or "terrible" in an audit
 * report.
 * </p>
 *
 * <h2>Tag normalisation</h2>
 *
 * <p>
 * Tags are trimmed, lower-cased with {@link Locale#ROOT} and de-duplicated, and
 * blank tags are dropped, so {@code "Crypto "} and {@code "crypto"} are the same
 * taxonomy tag. The taxonomy is a closed lower-case vocabulary, so this never
 * merges distinct tags.
 * </p>
 *
 * <p>
 * This class is stateless and thread-safe.
 * </p>
 *
 * @see AccuracyEvaluator
 * @since 6.1.0
 */
public final class AccuracyMetrics {

    private static final double TWO = 2.0;

    private AccuracyMetrics() {
        // static utility; no instances
    }

    /**
     * One method for which a prediction is compared with a human label.
     *
     * @param id                 stable identifier, used only for diagnostics
     * @param predictedRelevant  the AI's {@code securityRelevant} decision
     * @param truthRelevant      the human {@code securityRelevant} label, or
     *                           {@code null} when the label does not decide
     *                           relevance (the sample is then excluded from the
     *                           relevance confusion matrix)
     * @param predictedTags      the AI's tags; {@code null} is treated as empty
     * @param truthTags          the human tag set, or {@code null} when the label
     *                           does not decide tags (the sample is then excluded
     *                           from the tag metrics); an empty set is a real
     *                           label meaning "no tags"
     */
    public record Sample(String id, boolean predictedRelevant, Boolean truthRelevant,
            Collection<String> predictedTags, Collection<String> truthTags) {
    }

    /**
     * Confusion counts for one binary decision.
     *
     * @param tp true positives
     * @param fp false positives
     * @param fn false negatives
     * @param tn true negatives
     */
    public record Counts(int tp, int fp, int fn, int tn) {

        /**
         * Returns the number of truly positive samples.
         *
         * @return {@code tp + fn}
         */
        public int support() {
            return tp + fn;
        }

        /**
         * Returns the total number of samples counted.
         *
         * @return {@code tp + fp + fn + tn}
         */
        public int total() {
            return tp + fp + fn + tn;
        }

        /**
         * Returns the precision.
         *
         * @return {@code tp/(tp+fp)}, or empty when nothing was predicted positive
         */
        public OptionalDouble precision() {
            return ratio(tp, tp + fp);
        }

        /**
         * Returns the recall.
         *
         * @return {@code tp/(tp+fn)}, or empty when there are no true positives
         *         in the truth (zero support)
         */
        public OptionalDouble recall() {
            return ratio(tp, tp + fn);
        }

        /**
         * Returns the F1 score, the harmonic mean of precision and recall.
         *
         * @return {@code 2tp/(2tp+fp+fn)}, or empty when there is neither a
         *         predicted nor a true positive
         */
        public OptionalDouble f1() {
            return ratio(TWO * tp, TWO * tp + fp + fn);
        }

        /**
         * Returns the accuracy.
         *
         * @return {@code (tp+tn)/total}, or empty when no samples were counted
         */
        public OptionalDouble accuracy() {
            return ratio(tp + tn, total());
        }

        private static OptionalDouble ratio(double numerator, double denominator) {
            return denominator == 0.0 ? OptionalDouble.empty() : OptionalDouble.of(numerator / denominator);
        }
    }

    /**
     * Metrics of a single taxonomy tag.
     *
     * @param tag    normalised tag
     * @param counts one-vs-rest counts; {@link Counts#support()} is the number of
     *               labelled methods carrying the tag
     */
    public record TagResult(String tag, Counts counts) {
    }

    /**
     * The complete metric set.
     *
     * @param relevance       securityRelevant confusion counts
     * @param tags            per-tag results sorted by tag name; includes every
     *                        tag that appears in any prediction or label
     * @param tagsMicro       tag counts summed over all tags (micro-average basis)
     * @param tagsMacroF1     unweighted mean of the per-tag F1 over <em>every</em> tag
     *                        present in any prediction or label (including tags with
     *                        zero support, i.e. ones the AI invented, which score F1
     *                        0); empty when no tag occurs at all
     * @param relevanceSamples number of samples that took part in
     *                        {@code relevance}
     * @param tagSamples      number of samples that took part in the tag metrics
     */
    public record Result(Counts relevance, List<TagResult> tags, Counts tagsMicro,
            OptionalDouble tagsMacroF1, int relevanceSamples, int tagSamples) {
    }

    /**
     * Computes all metrics for the given samples.
     *
     * @param samples prediction/label pairs; must not be {@code null}; may be
     *                empty, which yields all-zero counts and undefined ratios
     * @return the metrics; never {@code null}; per-tag list sorted by tag
     */
    public static Result compute(Collection<Sample> samples) {
        Tally relevance = new Tally();
        Map<String, Tally> perTag = new TreeMap<>();
        int relevanceSamples = 0;
        int tagSamples = 0;

        for (Sample s : samples) {
            if (s.truthRelevant() != null) {
                relevanceSamples++;
                relevance.add(s.predictedRelevant(), s.truthRelevant());
            }
            if (s.truthTags() != null) {
                tagSamples++;
                accumulateTags(perTag, normalise(s.predictedTags()), normalise(s.truthTags()));
            }
        }
        return summarise(relevance, relevanceSamples, perTag, tagSamples);
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static Result summarise(Tally relevance, int relevanceSamples, Map<String, Tally> perTag,
            int tagSamples) {
        List<TagResult> tags = new ArrayList<>(perTag.size());
        int microTp = 0;
        int microFp = 0;
        int microFn = 0;
        double f1Sum = 0.0;
        int f1Count = 0;
        for (Map.Entry<String, Tally> e : perTag.entrySet()) {
            Tally c = e.getValue();
            Counts counts = new Counts(c.tp, c.fp, c.fn, tagSamples - c.tp - c.fp - c.fn);
            tags.add(new TagResult(e.getKey(), counts));
            microTp += counts.tp();
            microFp += counts.fp();
            microFn += counts.fn();
            // Every tag seen in a prediction or a label counts (scikit-learn convention): a tag the AI
            // invented has zero support and F1 0, so hallucinated tags pull the macro average down.
            f1Sum += counts.f1().orElse(0.0);
            f1Count++;
        }
        OptionalDouble macro = f1Count == 0 ? OptionalDouble.empty() : OptionalDouble.of(f1Sum / f1Count);
        // Micro tn is not meaningful across tags (it would sum unrelated one-vs-rest
        // negatives), so the micro row carries tn = 0 and its accuracy is not reported.
        Counts micro = new Counts(microTp, microFp, microFn, 0);
        Counts relevanceCounts = new Counts(relevance.tp, relevance.fp, relevance.fn, relevance.tn);
        return new Result(relevanceCounts, List.copyOf(tags), micro, macro, relevanceSamples, tagSamples);
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static void accumulateTags(Map<String, Tally> perTag, Set<String> predicted, Set<String> truth) {
        for (String t : predicted) {
            perTag.computeIfAbsent(t, k -> new Tally()).add(true, truth.contains(t));
        }
        for (String t : truth) {
            if (!predicted.contains(t)) {
                perTag.computeIfAbsent(t, k -> new Tally()).add(false, true);
            }
        }
    }

    /** Mutable confusion tally used while accumulating; never escapes this class. */
    private static final class Tally {
        /* default */ int tp;
        /* default */ int fp;
        /* default */ int fn;
        /* default */ int tn;

        /* default */ void add(boolean predicted, boolean truth) {
            if (predicted && truth) {
                tp++;
            } else if (predicted) {
                fp++;
            } else if (truth) {
                fn++;
            } else {
                tn++;
            }
        }
    }

    /**
     * Normalises a tag collection (trim, lower-case with {@link Locale#ROOT},
     * drop blanks, de-duplicate).
     *
     * @param tags tags to normalise; {@code null} is treated as empty
     * @return sorted, normalised tag set; never {@code null}
     */
    public static Set<String> normalise(Collection<String> tags) {
        Set<String> out = new TreeSet<>();
        if (tags != null) {
            for (String t : tags) {
                if (t != null && !t.isBlank()) {
                    out.add(t.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
