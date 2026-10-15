package org.egothor.methodatlas.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.egothor.methodatlas.eval.AccuracyEvaluator.Evaluation;
import org.egothor.methodatlas.eval.AccuracyMetrics.Sample;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Determinism and content tests of the Markdown and JSON renderers.
 */
class AccuracyReportRendererTest {

    private static Evaluation demonstrator() throws IOException {
        return AccuracyEvaluator.evaluate(AccuracyEvaluatorTest.EXAMPLE_DIR.resolve("labels.yaml"),
                AccuracyEvaluatorTest.EXAMPLE_DIR.resolve("predictions.csv"));
    }

    @Test
    void markdownContainsTheDocumentedNumbers() throws IOException {
        String md = AccuracyReportRenderer.markdown(demonstrator());

        assertTrue(md.startsWith("# MethodAtlas AI accuracy evaluation\n\n## Coverage\n"), md);
        assertTrue(md.contains("| Evaluated (labelled and predicted) | 6 |\n"), md);
        assertTrue(md.contains("| **Predicted: relevant** | TP 3 | FP 1 |\n"), md);
        assertTrue(md.contains("| **Predicted: not relevant** | FN 1 | TN 1 |\n"), md);
        assertTrue(md.contains("| F1 | 0.7500 |\n"), md);
        assertTrue(md.contains("| auth | 1.0000 | 0.5000 | 0.6667 | 2 | 1 | 0 | 1 |\n"), md);
        assertTrue(md.contains("| logging | 0.0000 | n/a | 0.0000 | 0 | 0 | 1 | 0 |\n"), md);
        assertTrue(md.contains("| **micro-average** | 0.6250 | 0.6250 | 0.6250 | 8 | 5 | 3 | 3 |\n"), md);
        assertTrue(md.endsWith("|\n"), "ends with a single trailing newline");
    }

    @Test
    void outputUsesOnlyLfAndIsIndependentOfDefaultLocale() throws IOException {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String md = AccuracyReportRenderer.markdown(demonstrator());
            String json = AccuracyReportRenderer.json(demonstrator());

            assertFalse(md.contains("\r"), "no CR in Markdown");
            assertFalse(json.contains("\r"), "no CR in JSON");
            assertTrue(md.contains("0.7500"), "dot decimal separator under de_DE");
            assertFalse(md.contains("0,7500"), "no locale decimal comma");
            assertTrue(json.contains("0.7500"));

            Locale.setDefault(Locale.ROOT);
            assertEquals(md, AccuracyReportRenderer.markdown(demonstrator()), "byte-identical across locales");
            assertEquals(json, AccuracyReportRenderer.json(demonstrator()));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void repeatedRenderingIsByteIdentical() throws IOException {
        assertEquals(AccuracyReportRenderer.markdown(demonstrator()), AccuracyReportRenderer.markdown(demonstrator()));
        assertEquals(AccuracyReportRenderer.json(demonstrator()), AccuracyReportRenderer.json(demonstrator()));
    }

    @Test
    void jsonIsWellFormedWithStableFieldsAndNullsForUndefined() throws IOException {
        String json = AccuracyReportRenderer.json(demonstrator());
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals(AccuracyReportRenderer.JSON_SCHEMA_VERSION, root.get("schemaVersion").asInt());
        assertEquals(6, root.get("coverage").get("evaluated").asInt());
        JsonNode rel = root.get("securityRelevant");
        assertEquals(3, rel.get("tp").asInt());
        assertEquals(1, rel.get("tn").asInt());
        assertEquals(0.75, rel.get("f1").asDouble(), 1e-9);
        JsonNode logging = null;
        for (JsonNode t : root.get("tags")) {
            if ("logging".equals(t.get("tag").asString())) {
                logging = t;
            }
        }
        assertTrue(logging != null && logging.get("recall").isNull(), "undefined recall is JSON null");
        assertEquals(0.625, root.get("tagsMicro").get("f1").asDouble(), 1e-9);
        assertTrue(json.endsWith("}\n"));
    }

    @Test
    void emptyEvaluationRendersUndefinedMetricsWithoutErrors() throws IOException {
        Evaluation empty = new Evaluation(AccuracyMetrics.compute(List.of()), 0, 0, 0, 0, 0);

        String md = AccuracyReportRenderer.markdown(empty);
        assertTrue(md.contains("| Precision | n/a |\n"), md);
        assertTrue(md.contains("| **macro-average** | | | n/a | | | | |\n"), md);

        String json = AccuracyReportRenderer.json(empty);
        JsonNode root = new ObjectMapper().readTree(json);
        assertTrue(root.get("securityRelevant").get("f1").isNull());
        assertEquals(0, root.get("tags").size());
        assertTrue(root.get("tagsMacroF1").isNull());
    }

    @Test
    void jsonEscapesSpecialCharactersInTagNames() throws IOException {
        AccuracyMetrics.Result m = AccuracyMetrics.compute(List.of(
                new Sample("a", true, true, List.of("we\"ird\\ta\ng\u0001x"), List.of())));
        Evaluation ev = new Evaluation(m, 1, 1, 0, 0, 0);

        JsonNode root = new ObjectMapper().readTree(AccuracyReportRenderer.json(ev));

        assertEquals("we\"ird\\ta\ng\u0001x",root.get("tags").get(0).get("tag").asString());
    }
}
