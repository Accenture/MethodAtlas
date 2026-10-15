package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code -eval-accuracy} is wired into the entry point and the
 * {@code -help} screen, without scanning anything or calling an AI provider.
 */
class MethodAtlasAppEvalAccuracyTest {

    private static final Path DIR = Path.of("docs", "examples", "accuracy");

    private static String run(int[] exit, String... args) throws Exception {
        StringWriter buffer = new StringWriter();
        try (PrintWriter out = new PrintWriter(buffer)) {
            exit[0] = MethodAtlasApp.run(args, out);
        }
        return buffer.toString();
    }

    @Test
    void evalAccuracyRunsThroughTheEntryPointAndIgnoresScanFlags() throws Exception {
        int[] exit = new int[1];
        String out = run(exit, "-ai", "-eval-accuracy", DIR.resolve("labels.yaml").toString(),
                DIR.resolve("predictions.csv").toString(), "-eval-format", "json");

        assertEquals(0, exit[0]);
        assertTrue(out.contains("\"securityRelevant\""), out);
    }

    @Test
    void helpDocumentsTheEvaluationFlags() throws Exception {
        int[] exit = new int[1];
        String out = run(exit, "-help");

        assertEquals(0, exit[0]);
        assertTrue(out.contains("-eval-accuracy"), out);
        assertTrue(out.contains("-eval-min-f1"), out);
    }
}
