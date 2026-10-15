package org.egothor.methodatlas.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.egothor.methodatlas.api.ScanRecord;
import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.eval.AccuracyEvaluator.Evaluation;
import org.egothor.methodatlas.eval.AccuracyMetrics.Counts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests label/prediction joining, including missing and extra entries.
 */
class AccuracyEvaluatorTest {

    /** The shipped demonstrator, also referenced from docs/ai/accuracy-evaluation.md. */
    static final Path EXAMPLE_DIR = Path.of("docs", "examples", "accuracy");

    private static final double EPS = 1e-9;

    private static ScanRecord record(String fqcn, String method, Boolean relevant, List<String> aiTags) {
        return new ScanRecord(fqcn, method, 1, List.of(), null, null, relevant, null, aiTags, null, null, null,
                null, null);
    }

    private static ClassificationOverride labels(Path dir, String yaml) throws IOException {
        Path f = dir.resolve("labels.yaml");
        Files.writeString(f, yaml, StandardCharsets.UTF_8);
        return ClassificationOverride.load(f);
    }

    @Test
    void demonstratorProducesTheDocumentedNumbers() throws IOException {
        Evaluation ev = AccuracyEvaluator.evaluate(EXAMPLE_DIR.resolve("labels.yaml"),
                EXAMPLE_DIR.resolve("predictions.csv"));

        assertEquals(7, ev.predictionRows());
        assertEquals(6, ev.evaluated());
        assertEquals(1, ev.unlabelledPredictions());
        assertEquals(0, ev.unpredictedLabels());
        assertEquals(1, ev.staleLabelEntries());
        assertEquals(new Counts(3, 1, 1, 1), ev.metrics().relevance());
        assertEquals(0.75, ev.metrics().relevance().f1().getAsDouble(), EPS);
        assertEquals(0.625, ev.metrics().tagsMicro().f1().getAsDouble(), EPS);
        // Five tags: security, auth, crypto and the two invented ones (logging,
        // input-validation), which score F1 0 and are included in the macro average.
        assertEquals((0.75 + 2.0 / 3.0 + 2.0 / 3.0) / 5.0, ev.metrics().tagsMacroF1().getAsDouble(), EPS);
    }

    @Test
    void methodLevelLabelWinsOverClassLevelAndLastDuplicateWins(@TempDir Path tmp) throws IOException {
        ClassificationOverride l = labels(tmp, """
                overrides:
                  - fqcn: a.T
                    securityRelevant: false
                  - fqcn: a.T
                    method: m1
                    securityRelevant: true
                  - fqcn: a.T
                    method: m1
                    securityRelevant: false
                """);

        Evaluation ev = AccuracyEvaluator.evaluate(l,
                List.of(record("a.T", "m1", true, List.of()), record("a.T", "m2", true, List.of())));

        assertEquals(new Counts(0, 2, 0, 0), ev.metrics().relevance());
    }

    @Test
    void missingPredictionAndMissingLabelAreCountedNotScored(@TempDir Path tmp) throws IOException {
        ClassificationOverride l = labels(tmp, """
                overrides:
                  - fqcn: a.T
                    method: noAi
                    securityRelevant: true
                  - fqcn: a.T
                    method: gone
                    securityRelevant: true
                """);

        Evaluation ev = AccuracyEvaluator.evaluate(l,
                List.of(record("a.T", "noAi", null, null), record("a.T", "extra", false, List.of())));

        assertEquals(0, ev.evaluated());
        assertEquals(1, ev.unpredictedLabels());
        assertEquals(1, ev.unlabelledPredictions());
        assertEquals(1, ev.staleLabelEntries());
        assertEquals(0, ev.metrics().relevanceSamples());
    }

    @Test
    void noteOnlyEntryIsNotALabel(@TempDir Path tmp) throws IOException {
        ClassificationOverride l = labels(tmp, """
                overrides:
                  - fqcn: a.T
                    method: m
                    note: "just a remark"
                """);

        Evaluation ev = AccuracyEvaluator.evaluate(l, List.of(record("a.T", "m", true, List.of("auth"))));

        assertEquals(0, ev.evaluated());
        assertEquals(1, ev.unlabelledPredictions());
        assertEquals(0, ev.staleLabelEntries());
    }

    @Test
    void notRelevantWithoutTagsLabelsAnEmptyTagSetButRelevantWithoutTagsDoesNot(@TempDir Path tmp)
            throws IOException {
        ClassificationOverride l = labels(tmp, """
                overrides:
                  - fqcn: a.T
                    method: neg
                    securityRelevant: false
                  - fqcn: a.T
                    method: pos
                    securityRelevant: true
                """);

        Evaluation ev = AccuracyEvaluator.evaluate(l,
                List.of(record("a.T", "neg", true, List.of("auth")), record("a.T", "pos", true, List.of("auth"))));

        assertEquals(2, ev.metrics().relevanceSamples());
        assertEquals(1, ev.metrics().tagSamples());
        assertEquals(new Counts(0, 1, 0, 0), ev.metrics().tags().get(0).counts());
    }

    @Test
    void emptyLabelFileYieldsZeroEvaluated(@TempDir Path tmp) throws IOException {
        ClassificationOverride l = labels(tmp, "overrides: []\n");

        Evaluation ev = AccuracyEvaluator.evaluate(l, List.of(record("a.T", "m", true, List.of())));

        assertEquals(0, ev.evaluated());
        assertEquals(1, ev.unlabelledPredictions());
    }

    @Test
    void missingFilesAreReportedAsIoErrors(@TempDir Path tmp) {
        Path predictions = EXAMPLE_DIR.resolve("predictions.csv");
        Path absentLabels = tmp.resolve("absent.yaml");
        assertThrows(IOException.class, () -> AccuracyEvaluator.evaluate(absentLabels, predictions));
        Path labelsFile = EXAMPLE_DIR.resolve("labels.yaml");
        Path absentCsv = tmp.resolve("absent.csv");
        assertThrows(IOException.class, () -> AccuracyEvaluator.evaluate(labelsFile, absentCsv));
    }

    @Test
    void csvWithoutAiColumnsCountsEveryLabelAsUnpredicted(@TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("plain.csv");
        Files.writeString(csv, "fqcn,method,loc,tags,display_name\na.T,m,3,,\n", StandardCharsets.UTF_8);
        labels(tmp, """
                overrides:
                  - fqcn: a.T
                    method: m
                    securityRelevant: true
                """);

        Evaluation ev = AccuracyEvaluator.evaluate(tmp.resolve("labels.yaml"), csv);

        assertEquals(1, ev.predictionRows());
        assertEquals(1, ev.unpredictedLabels());
        assertTrue(ev.metrics().relevance().f1().isEmpty());
    }
}
