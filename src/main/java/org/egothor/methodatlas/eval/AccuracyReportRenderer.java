// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Renders an {@link AccuracyEvaluator.Evaluation} as Markdown or JSON.
 *
 * <h2>Determinism</h2>
 *
 * <p>
 * The output is a pure function of the evaluation: lines end with {@code \n} on
 * every platform (never the platform line separator), numbers are formatted with
 * {@link Locale#ROOT} to four decimal places (never a locale decimal comma), tags
 * are listed in sorted order, and the report contains no timestamp, path or host
 * information. Two runs over the same inputs are therefore byte-identical on any
 * operating system, in line with the cross-platform determinism contract of the
 * scan output.
 * </p>
 *
 * <p>
 * An undefined metric (zero denominator, see {@link AccuracyMetrics}) is rendered
 * as {@code n/a} in Markdown and as JSON {@code null}.
 * </p>
 *
 * <p>
 * This class is stateless and thread-safe.
 * </p>
 *
 * @see AccuracyEvaluator
 * @since 6.1.0
 */
public final class AccuracyReportRenderer {

    /** JSON report schema version; bump on any breaking field change. */
    public static final int JSON_SCHEMA_VERSION = 1;

    private static final char LF = '\n';
    private static final String NOT_AVAILABLE = "n/a";
    private static final String JSON_NULL = "null";
    private static final String NUMBER_FORMAT = "%.4f";
    private static final String JSON_SEP = ", ";
    private static final String EMPTY_CELL = " |";
    private static final String LEFT = "---";
    private static final String RIGHT = "---:";
    private static final int BUILDER_CAPACITY = 2048;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int CONTROL_LIMIT = 0x20;
    private static final int NIBBLE_SHIFT = 4;
    private static final int NIBBLE_MASK = 0xF;

    private AccuracyReportRenderer() {
        // static utility; no instances
    }

    /**
     * Renders the evaluation as a Markdown document.
     *
     * @param ev evaluation to render; must not be {@code null}
     * @return Markdown text with {@code \n} line endings, ending in a newline
     */
    public static String markdown(AccuracyEvaluator.Evaluation ev) {
        AccuracyMetrics.Result m = ev.metrics();
        AccuracyMetrics.Counts r = m.relevance();
        StringBuilder sb = new StringBuilder(BUILDER_CAPACITY);
        line(sb, "# MethodAtlas AI accuracy evaluation\n");

        line(sb, "## Coverage\n");
        row(sb, "Item", "Count");
        row(sb, LEFT, RIGHT);
        row(sb, "Prediction rows read", String.valueOf(ev.predictionRows()));
        row(sb, "Evaluated (labelled and predicted)", String.valueOf(ev.evaluated()));
        row(sb, "Predictions without a label", String.valueOf(ev.unlabelledPredictions()));
        row(sb, "Labelled methods without an AI prediction", String.valueOf(ev.unpredictedLabels()));
        row(sb, "Label entries matching no scanned method", String.valueOf(ev.staleLabelEntries()));
        line(sb, "");

        line(sb, "## securityRelevant\n");
        line(sb, "Samples: " + m.relevanceSamples() + "\n");
        row(sb, "", "Truth: relevant", "Truth: not relevant");
        row(sb, LEFT, RIGHT, RIGHT);
        row(sb, "**Predicted: relevant**", "TP " + r.tp(), "FP " + r.fp());
        row(sb, "**Predicted: not relevant**", "FN " + r.fn(), "TN " + r.tn());
        line(sb, "");
        row(sb, "Metric", "Value");
        row(sb, LEFT, RIGHT);
        row(sb, "Precision", format(r.precision()));
        row(sb, "Recall", format(r.recall()));
        row(sb, "F1", format(r.f1()));
        row(sb, "Accuracy", format(r.accuracy()));
        row(sb, "Support", String.valueOf(r.support()));
        line(sb, "");

        line(sb, "## Taxonomy tags\n");
        line(sb, "Samples: " + m.tagSamples() + "\n");
        row(sb, "Tag", "Precision", "Recall", "F1", "Support", "TP", "FP", "FN");
        row(sb, LEFT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT);
        for (AccuracyMetrics.TagResult t : m.tags()) {
            tagRow(sb, t.tag(), t.counts());
        }
        tagRow(sb, "**micro-average**", m.tagsMicro());
        row(sb, "**macro-average**", "", "", format(m.tagsMacroF1()), "", "", "", "");
        return sb.toString();
    }

    /**
     * Renders the evaluation as a JSON document.
     *
     * <p>
     * Keys appear in a fixed order. Undefined metrics are {@code null}.
     * </p>
     *
     * @param ev evaluation to render; must not be {@code null}
     * @return JSON text with {@code \n} line endings, ending in a newline
     */
    public static String json(AccuracyEvaluator.Evaluation ev) {
        AccuracyMetrics.Result m = ev.metrics();
        List<String> tagObjects = new ArrayList<>(m.tags().size());
        for (AccuracyMetrics.TagResult t : m.tags()) {
            tagObjects.add("    {\"tag\": " + quote(t.tag()) + JSON_SEP + countFields(t.counts(), false) + "}");
        }
        String tags = tagObjects.isEmpty() ? "[]" : "[\n" + String.join(",\n", tagObjects) + "\n  ]";

        StringBuilder sb = new StringBuilder(BUILDER_CAPACITY);
        line(sb, "{");
        line(sb, "  \"schemaVersion\": " + JSON_SCHEMA_VERSION + ",");
        line(sb, "  \"coverage\": {");
        line(sb, "    \"predictionRows\": " + ev.predictionRows() + ",");
        line(sb, "    \"evaluated\": " + ev.evaluated() + ",");
        line(sb, "    \"unlabelledPredictions\": " + ev.unlabelledPredictions() + ",");
        line(sb, "    \"unpredictedLabels\": " + ev.unpredictedLabels() + ",");
        line(sb, "    \"staleLabelEntries\": " + ev.staleLabelEntries());
        line(sb, "  },");
        line(sb, "  \"securityRelevant\": {" + countFields(m.relevance(), true) + "},");
        line(sb, "  \"tags\": " + tags + ",");
        line(sb, "  \"tagsMicro\": {" + countFields(m.tagsMicro(), false) + "},");
        line(sb, "  \"tagsMacroF1\": " + jsonNumber(m.tagsMacroF1()));
        line(sb, "}");
        return sb.toString();
    }

    /** Appends {@code text} followed by a single LF. */
    private static void line(StringBuilder sb, String text) {
        sb.append(text).append(LF);
    }

    /** Appends one Markdown table row; empty cells render as {@code | |}. */
    private static void row(StringBuilder sb, String... cells) {
        sb.append('|');
        for (String cell : cells) {
            sb.append(cell.isEmpty() ? EMPTY_CELL : " " + cell + EMPTY_CELL);
        }
        sb.append(LF);
    }

    private static void tagRow(StringBuilder sb, String name, AccuracyMetrics.Counts c) {
        row(sb, name, format(c.precision()), format(c.recall()), format(c.f1()),
                String.valueOf(c.support()), String.valueOf(c.tp()), String.valueOf(c.fp()),
                String.valueOf(c.fn()));
    }

    /** Returns the fields of a counts object without braces; {@code full} adds tn and accuracy. */
    private static String countFields(AccuracyMetrics.Counts c, boolean full) {
        List<String> fields = new ArrayList<>();
        fields.add("\"tp\": " + c.tp());
        fields.add("\"fp\": " + c.fp());
        fields.add("\"fn\": " + c.fn());
        if (full) {
            fields.add("\"tn\": " + c.tn());
        }
        fields.add("\"support\": " + c.support());
        fields.add("\"precision\": " + jsonNumber(c.precision()));
        fields.add("\"recall\": " + jsonNumber(c.recall()));
        fields.add("\"f1\": " + jsonNumber(c.f1()));
        if (full) {
            fields.add("\"accuracy\": " + jsonNumber(c.accuracy()));
        }
        return String.join(JSON_SEP, fields);
    }

    private static String format(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, NUMBER_FORMAT, value.getAsDouble()) : NOT_AVAILABLE;
    }

    private static String jsonNumber(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, NUMBER_FORMAT, value.getAsDouble()) : JSON_NULL;
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            appendEscaped(sb, s.charAt(i));
        }
        return sb.append('"').toString();
    }

    private static void appendEscaped(StringBuilder sb, char ch) {
        switch (ch) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default -> {
                if (ch < CONTROL_LIMIT) {
                    sb.append("\\u00").append(HEX[(ch >> NIBBLE_SHIFT) & NIBBLE_MASK]).append(HEX[ch & NIBBLE_MASK]);
                } else {
                    sb.append(ch);
                }
            }
        }
    }
}
