package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests argument parsing, output selection and the F1 gate of
 * {@link EvalAccuracyCommand}; no AI provider is involved.
 */
class EvalAccuracyCommandTest {

    private static final Path DIR = Path.of("docs", "examples", "accuracy");
    private static final String LABELS = DIR.resolve("labels.yaml").toString();
    private static final String PREDICTIONS = DIR.resolve("predictions.csv").toString();

    private static String run(EvalAccuracyCommand command, int[] exit) throws IOException {
        StringWriter buffer = new StringWriter();
        try (PrintWriter writer = new PrintWriter(buffer)) {
            exit[0] = command.execute(writer);
        }
        return buffer.toString();
    }

    @Test
    void defaultsToMarkdownAndExitsZeroWithoutGate() throws IOException {
        int[] exit = new int[1];
        String out = run(EvalAccuracyCommand.fromArgs("-eval-accuracy", LABELS, PREDICTIONS), exit);

        assertEquals(0, exit[0]);
        assertTrue(out.startsWith("# MethodAtlas AI accuracy evaluation\n"), out);
        assertFalse(out.contains("\r"), "report must use LF only");
    }

    @Test
    void jsonFormatIsSelectable() throws IOException {
        int[] exit = new int[1];
        String out = run(EvalAccuracyCommand.fromArgs("-eval-accuracy", LABELS, PREDICTIONS,
                "-eval-format", "JSON"), exit);

        assertEquals(0, exit[0]);
        assertTrue(out.startsWith("{\n  \"schemaVersion\": 1,"), out);
    }

    @Test
    void gateFailsBelowThresholdAndPassesAtOrAbove() throws IOException {
        int[] exit = new int[1];
        run(EvalAccuracyCommand.fromArgs("-eval-accuracy", LABELS, PREDICTIONS, "-eval-min-f1", "0.8"), exit);
        assertEquals(1, exit[0], "tag micro-F1 is 0.625, below 0.8");

        run(EvalAccuracyCommand.fromArgs("-eval-accuracy", LABELS, PREDICTIONS, "-eval-min-f1", "0.6"), exit);
        assertEquals(0, exit[0], "relevance F1 0.75 and tag micro-F1 0.625 both reach 0.6");

        run(EvalAccuracyCommand.fromArgs("-eval-accuracy", LABELS, PREDICTIONS, "-eval-min-f1", "0.625"), exit);
        assertEquals(0, exit[0], "threshold is inclusive");
    }

    @Test
    void gateFailsWhenNothingCouldBeEvaluated(@TempDir Path tmp) throws IOException {
        Path emptyLabels = tmp.resolve("labels.yaml");
        Files.writeString(emptyLabels, "overrides: []\n", StandardCharsets.UTF_8);
        int[] exit = new int[1];

        run(new EvalAccuracyCommand(emptyLabels, Path.of(PREDICTIONS), false, OptionalDouble.of(0.0)), exit);
        assertEquals(1, exit[0], "a gate must not pass on no evidence");

        run(new EvalAccuracyCommand(emptyLabels, Path.of(PREDICTIONS), false, OptionalDouble.empty()), exit);
        assertEquals(0, exit[0], "without a gate an empty label set is just reported");
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EvalAccuracyCommand.fromArgs("-eval-accuracy", LABELS));
        assertThrows(IllegalArgumentException.class, () -> EvalAccuracyCommand.fromArgs("-scan"));
        assertThrows(IllegalArgumentException.class, () -> EvalAccuracyCommand.fromArgs(
                "-eval-accuracy", LABELS, PREDICTIONS, "-eval-format", "xml"));
        assertThrows(IllegalArgumentException.class, () -> EvalAccuracyCommand.fromArgs(
                "-eval-accuracy", LABELS, PREDICTIONS, "-eval-format"));
        assertThrows(IllegalArgumentException.class, () -> EvalAccuracyCommand.fromArgs(
                "-eval-accuracy", LABELS, PREDICTIONS, "-eval-min-f1", "abc"));
        assertThrows(IllegalArgumentException.class, () -> EvalAccuracyCommand.fromArgs(
                "-eval-accuracy", LABELS, PREDICTIONS, "-eval-min-f1", "1.5"));
        assertThrows(IllegalArgumentException.class, () -> EvalAccuracyCommand.fromArgs(
                "-eval-accuracy", LABELS, PREDICTIONS, "-eval-min-f1", "NaN"));
    }

    @Test
    void unreadableInputIsAnIoError(@TempDir Path tmp) {
        EvalAccuracyCommand command = new EvalAccuracyCommand(tmp.resolve("absent.yaml"), Path.of(PREDICTIONS),
                false, OptionalDouble.empty());
        assertThrows(IOException.class, () -> command.execute(new PrintWriter(new StringWriter())));
    }
}
