// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.eval;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egothor.methodatlas.api.ScanRecord;
import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.emit.DeltaReport;

/**
 * Joins AI predictions with human-reviewed labels and scores them with
 * {@link AccuracyMetrics}.
 *
 * <h2>Ground truth</h2>
 *
 * <p>
 * The labels are a {@link ClassificationOverride} document: the same
 * human-reviewed YAML that {@code -override-file} applies to scans. Resolution
 * follows the override rules: a method-level entry wins over a class-level entry
 * for the same class, and the last duplicate wins. An entry yields:
 * </p>
 *
 * <ul>
 * <li>a <b>relevance label</b> when {@code securityRelevant} is present;</li>
 * <li>a <b>tag label</b> when {@code tags} is present, or — as a deliberate
 *     convention — an empty tag set when {@code securityRelevant: false} is
 *     present without tags (a reviewer who says "not security-relevant" asserts
 *     that no security tag applies). {@code securityRelevant: true} without
 *     {@code tags} labels relevance only.</li>
 * </ul>
 *
 * <p>
 * An entry that carries neither (for example a {@code note}-only entry) is not a
 * label and is ignored.
 * </p>
 *
 * <h2>Predictions</h2>
 *
 * <p>
 * Predictions are the {@code ai_security_relevant} and {@code ai_tags} columns of
 * a MethodAtlas scan CSV produced with {@code -ai}. The scan used to produce them
 * must <em>not</em> have been run with {@code -override-file}: overrides replace
 * the AI values and would make the evaluation score the human's labels against
 * themselves.
 * </p>
 *
 * <h2>Coverage bookkeeping</h2>
 *
 * <p>
 * Missing and extra entries never fail the evaluation; they are counted and
 * reported so that a reader can judge how representative the numbers are:
 * predictions without a label ({@link Evaluation#unlabelledPredictions()}),
 * labelled methods without an AI prediction
 * ({@link Evaluation#unpredictedLabels()}), and label entries that match no
 * scanned method or class ({@link Evaluation#staleLabelEntries()}).
 * </p>
 *
 * <p>
 * This class is stateless and thread-safe.
 * </p>
 *
 * @see AccuracyMetrics
 * @see AccuracyReportRenderer
 * @since 6.1.0
 */
public final class AccuracyEvaluator {

    private AccuracyEvaluator() {
        // static utility; no instances
    }

    /**
     * Metrics together with the coverage bookkeeping of the join.
     *
     * @param metrics                the computed quality metrics
     * @param predictionRows         number of rows read from the predictions CSV
     * @param evaluated              number of methods that were both labelled and
     *                               predicted and therefore took part in scoring
     * @param unlabelledPredictions  predicted methods without a usable label
     * @param unpredictedLabels      labelled methods whose row carries no AI
     *                               prediction
     * @param staleLabelEntries      label entries matching no row of the
     *                               predictions CSV (renamed or deleted methods,
     *                               or a different scan scope)
     */
    public record Evaluation(AccuracyMetrics.Result metrics, int predictionRows, int evaluated,
            int unlabelledPredictions, int unpredictedLabels, int staleLabelEntries) {
    }

    /** A resolved ground-truth label; either field may be {@code null} (undecided). */
    private record Label(Boolean relevant, Set<String> tags) {
    }

    /**
     * Loads the label file and the predictions CSV and evaluates them.
     *
     * @param labelsFile      override-format YAML holding the human-reviewed
     *                        labels; must exist
     * @param predictionsCsv  MethodAtlas scan CSV produced with {@code -ai}
     * @return the evaluation; never {@code null}
     * @throws IOException if either file cannot be read or parsed
     * @throws IllegalArgumentException if the CSV lacks the required
     *                                  {@code fqcn} or {@code method} columns
     */
    public static Evaluation evaluate(Path labelsFile, Path predictionsCsv) throws IOException {
        ClassificationOverride labels = ClassificationOverride.load(labelsFile);
        List<ScanRecord> predictions = DeltaReport.loadRecords(predictionsCsv);
        return evaluate(labels, predictions);
    }

    /**
     * Evaluates already-loaded labels against already-loaded predictions.
     *
     * @param labels      human-reviewed labels; must not be {@code null}
     * @param predictions scan records carrying the AI columns; must not be
     *                    {@code null}
     * @return the evaluation; never {@code null}
     */
    public static Evaluation evaluate(ClassificationOverride labels, List<ScanRecord> predictions) {
        List<ClassificationOverride.Entry> entries = labels.entries();
        Map<String, Label> classLevel = new HashMap<>();
        Map<String, Label> methodLevel = new HashMap<>();
        for (ClassificationOverride.Entry e : entries) {
            Label label = toLabel(e);
            if (e.method() == null) {
                classLevel.put(e.fqcn(), label);
            } else {
                methodLevel.put(key(e.fqcn(), e.method()), label);
            }
        }

        List<AccuracyMetrics.Sample> samples = new ArrayList<>();
        Set<String> seenMethods = new HashSet<>();
        Set<String> seenClasses = new HashSet<>();
        int unlabelled = 0;
        int unpredicted = 0;
        for (ScanRecord r : predictions) {
            seenMethods.add(key(r.fqcn(), r.method()));
            seenClasses.add(r.fqcn());
            Label label = methodLevel.getOrDefault(key(r.fqcn(), r.method()), classLevel.get(r.fqcn()));
            if (label == null || (label.relevant() == null && label.tags() == null)) {
                if (r.aiSecurityRelevant() != null) {
                    unlabelled++;
                }
            } else if (r.aiSecurityRelevant() == null) {
                unpredicted++;
            } else {
                samples.add(new AccuracyMetrics.Sample(key(r.fqcn(), r.method()),
                        r.aiSecurityRelevant(), label.relevant(), r.aiTags(), label.tags()));
            }
        }

        int stale = 0;
        for (ClassificationOverride.Entry e : entries) {
            boolean matched = e.method() == null
                    ? seenClasses.contains(e.fqcn())
                    : seenMethods.contains(key(e.fqcn(), e.method()));
            if (!matched) {
                stale++;
            }
        }

        return new Evaluation(AccuracyMetrics.compute(samples), predictions.size(), samples.size(),
                unlabelled, unpredicted, stale);
    }

    private static Label toLabel(ClassificationOverride.Entry e) {
        Set<String> tags = null;
        if (e.tags() != null) {
            tags = AccuracyMetrics.normalise(e.tags());
        } else if (Boolean.FALSE.equals(e.securityRelevant())) {
            tags = Set.of();
        }
        return new Label(e.securityRelevant(), tags);
    }

    private static String key(String fqcn, String method) {
        return fqcn + "::" + method;
    }
}
