// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalDouble;

import org.egothor.methodatlas.eval.AccuracyEvaluator;
import org.egothor.methodatlas.eval.AccuracyMetrics;
import org.egothor.methodatlas.eval.AccuracyReportRenderer;

/**
 * Utility CLI mode ({@code -eval-accuracy}) that measures how accurate the AI
 * classification is against a human-reviewed labelled set.
 *
 * <p>
 * Synopsis:
 * {@code methodatlas -eval-accuracy <labels.yaml> <predictions.csv> [-eval-format markdown|json] [-eval-min-f1 <0..1>]}
 * </p>
 *
 * <ul>
 * <li>{@code labels.yaml} — the ground truth, in the {@code -override-file}
 *     format (the recommended source: those entries are already human-reviewed);</li>
 * <li>{@code predictions.csv} — a scan CSV produced with {@code -ai} and
 *     <em>without</em> {@code -override-file}, so the AI columns hold genuine AI
 *     output;</li>
 * <li>{@code -eval-format} — {@code markdown} (default) or {@code json};</li>
 * <li>{@code -eval-min-f1} — optional gate: exit {@code 1} when the F1 of the
 *     {@code securityRelevant} decision or the micro-averaged tag F1 is below the
 *     threshold, or when neither could be computed (no labelled data — a gate must
 *     not pass on no evidence).</li>
 * </ul>
 *
 * <p>
 * No scan runs and no AI provider is called: the command only reads the two
 * files, so it is deterministic and offline. The report is written with
 * {@code \n} line endings regardless of platform.
 * </p>
 *
 * @see AccuracyEvaluator
 * @see AccuracyMetrics
 * @since 6.1.0
 */
public final class EvalAccuracyCommand implements Command {

    /** Flag that selects this utility mode; takes two path arguments. */
    public static final String FLAG_EVAL_ACCURACY = "-eval-accuracy";

    /** Flag selecting the report format. */
    public static final String FLAG_EVAL_FORMAT = "-eval-format";

    /** Flag setting the minimum acceptable F1. */
    public static final String FLAG_EVAL_MIN_F1 = "-eval-min-f1";

    private static final int EXIT_GATE_FAILED = 1;
    private static final String FORMAT_MARKDOWN = "markdown";
    private static final String FORMAT_JSON = "json";
    private static final int ARGS_AFTER_FLAG = 2;

    private final Path labels;
    private final Path predictions;
    private final boolean json;
    private final OptionalDouble minF1;

    /**
     * Creates a command.
     *
     * @param labels      override-format YAML with the human-reviewed labels
     * @param predictions scan CSV produced with {@code -ai}
     * @param json        {@code true} for JSON output, {@code false} for Markdown
     * @param minF1       minimum acceptable F1 in {@code [0,1]}, or empty for no
     *                    gate
     */
    public EvalAccuracyCommand(Path labels, Path predictions, boolean json, OptionalDouble minF1) {
        this.labels = labels;
        this.predictions = predictions;
        this.json = json;
        this.minF1 = minF1;
    }

    /**
     * Builds the command from raw command-line arguments.
     *
     * @param args raw arguments; must contain {@code -eval-accuracy} followed by
     *             the labels path and the predictions path
     * @return a configured command; never {@code null}
     * @throws IllegalArgumentException if the paths are missing, the format is
     *                                  neither {@code markdown} nor {@code json},
     *                                  or the threshold is not a number in
     *                                  {@code [0,1]}
     */
    public static EvalAccuracyCommand fromArgs(String... args) {
        int at = indexOf(args, FLAG_EVAL_ACCURACY);
        if (at < 0 || at + ARGS_AFTER_FLAG >= args.length) {
            throw new IllegalArgumentException(
                    FLAG_EVAL_ACCURACY + " requires two arguments: " + FLAG_EVAL_ACCURACY
                            + " <labels.yaml> <predictions.csv>");
        }
        boolean asJson = false;
        int fmt = indexOf(args, FLAG_EVAL_FORMAT);
        if (fmt >= 0) {
            String value = valueAfter(args, fmt, FLAG_EVAL_FORMAT).toLowerCase(Locale.ROOT);
            if (FORMAT_JSON.equals(value)) {
                asJson = true;
            } else if (!FORMAT_MARKDOWN.equals(value)) {
                throw new IllegalArgumentException(
                        FLAG_EVAL_FORMAT + " must be 'markdown' or 'json', got: " + value);
            }
        }
        OptionalDouble threshold = OptionalDouble.empty();
        int min = indexOf(args, FLAG_EVAL_MIN_F1);
        if (min >= 0) {
            threshold = OptionalDouble.of(parseThreshold(valueAfter(args, min, FLAG_EVAL_MIN_F1)));
        }
        return new EvalAccuracyCommand(Path.of(args[at + 1]), Path.of(args[at + 2]), asJson, threshold);
    }

    /**
     * Evaluates the predictions against the labels and writes the report.
     *
     * @param out writer that receives the report; never {@code null}
     * @return {@code 0} on success or when no gate is set; {@code 1} when the
     *         {@code -eval-min-f1} gate fails
     * @throws IOException if either input file cannot be read or parsed
     */
    @Override
    public int execute(PrintWriter out) throws IOException {
        AccuracyEvaluator.Evaluation ev = AccuracyEvaluator.evaluate(labels, predictions);
        out.print(json ? AccuracyReportRenderer.json(ev) : AccuracyReportRenderer.markdown(ev));
        out.flush();
        if (minF1.isEmpty()) {
            return 0;
        }
        return gate(ev.metrics(), minF1.getAsDouble()) ? 0 : EXIT_GATE_FAILED;
    }

    /**
     * Applies the F1 gate: every defined F1 among relevance and micro tags must
     * reach the threshold, and at least one must be defined.
     */
    private static boolean gate(AccuracyMetrics.Result m, double threshold) {
        OptionalDouble relevanceF1 = m.relevance().f1();
        OptionalDouble tagF1 = m.tagsMicro().f1();
        if (relevanceF1.isEmpty() && tagF1.isEmpty()) {
            return false;
        }
        boolean relevanceOk = relevanceF1.isEmpty() || relevanceF1.getAsDouble() >= threshold;
        boolean tagOk = tagF1.isEmpty() || tagF1.getAsDouble() >= threshold;
        return relevanceOk && tagOk;
    }

    private static double parseThreshold(String text) {
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(FLAG_EVAL_MIN_F1 + " must be a number in [0,1], got: " + text, e);
        }
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(FLAG_EVAL_MIN_F1 + " must be in [0,1], got: " + text);
        }
        return value;
    }

    private static String valueAfter(String[] args, int flagIndex, String flag) {
        if (flagIndex + 1 >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[flagIndex + 1];
    }

    private static int indexOf(String[] args, String flag) {
        for (int i = 0; i < args.length; i++) {
            if (flag.equals(args[i])) {
                return i;
            }
        }
        return -1;
    }
}
