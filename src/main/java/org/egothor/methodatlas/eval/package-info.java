// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
/**
 * AI-classification accuracy evaluation: scores AI predictions against a
 * human-reviewed labelled set (the {@code ClassificationOverride} YAML format)
 * and reports precision, recall, F1 and support per taxonomy tag and for the
 * {@code securityRelevant} decision.
 *
 * <p>
 * {@link org.egothor.methodatlas.eval.AccuracyMetrics} is the pure metric math,
 * {@link org.egothor.methodatlas.eval.AccuracyEvaluator} joins labels with
 * predictions, and {@link org.egothor.methodatlas.eval.AccuracyReportRenderer}
 * renders Markdown or JSON. Nothing in this package calls an AI provider.
 * </p>
 */
package org.egothor.methodatlas.eval;
