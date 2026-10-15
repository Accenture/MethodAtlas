package org.egothor.methodatlas.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.api.CredentialCandidate;
import org.egothor.methodatlas.api.CredentialCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for cross-platform determinism of the streaming emitters
 * (roadmap RM-D, SABSA exception EX-005): line terminators and number
 * formatting must not depend on the host operating system or default locale.
 *
 * <p>
 * The Windows line terminator is simulated by a {@link PrintWriter} whose
 * {@code println} writes {@code \r\n}, so these tests fail on every OS if an
 * emitter falls back to {@code println}.
 * </p>
 */
class StructuralOutputDeterminismTest {

    private Locale savedLocale;

    @BeforeEach
    void saveLocale() {
        savedLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(savedLocale);
    }

    /** A writer that behaves like {@code System.lineSeparator() == "\r\n"}. */
    private static final class WindowsPrintWriter extends PrintWriter {
        WindowsPrintWriter(StringWriter sink) {
            super(sink);
        }

        @Override
        public void println() {
            write("\r\n");
        }

        @Override
        public void println(String x) {
            write(x);
            println();
        }
    }

    private static String capture(Consumer<PrintWriter> action) {
        StringWriter sink = new StringWriter();
        try (PrintWriter out = new WindowsPrintWriter(sink)) {
            action.accept(out);
        }
        return sink.toString();
    }

    private static AiMethodSuggestion suggestion() {
        return new AiMethodSuggestion("m", true, "name", List.of("security", "auth"), "why", 0.5, 0.25);
    }

    @Test
    void lineEnding_println_terminatesWithLfOnly() {
        String out = capture(pw -> LineEnding.println(pw, "row"));
        assertEquals("row\n", out);
    }

    @Test
    void outputEmitter_csv_usesLfOnly() {
        String out = capture(pw -> {
            OutputEmitter e = new OutputEmitter(pw, false, false, true, false, true, false);
            e.emitMetadata("dev", "t", "tax");
            e.emitCsvHeader(OutputMode.CSV);
            e.emit(OutputMode.CSV, "a.B", "m", 3, "h", List.of("x"), "", null, "src/");
        });
        assertFalse(out.contains("\r"), "CSV output must not contain CR: " + out);
        assertEquals(5, out.split("\n", -1).length - 1, "one LF per line");
    }

    @Test
    void outputEmitter_plain_usesLfOnly() {
        String out = capture(pw -> {
            OutputEmitter e = new OutputEmitter(pw, false, false, true, false, true, false);
            e.emit(OutputMode.PLAIN, "a.B", "m", 3, "h", List.of(), "", null, "src/");
        });
        assertFalse(out.contains("\r"));
        assertTrue(out.endsWith("\n"));
    }

    @Test
    void jsonEmitter_usesLfOnly() {
        String out = capture(pw -> {
            JsonEmitter e = new JsonEmitter(false, false, true, false, false);
            e.record("a.B", "m", 1, 3, "h", List.of("x"), "", null);
            e.flush(pw);
        });
        assertFalse(out.contains("\r"), "JSON output must not contain CR");
        assertTrue(out.endsWith("\n"));
    }

    @Test
    void githubAnnotations_usesLfOnly() {
        String out = capture(pw -> {
            GitHubAnnotationsEmitter e = new GitHubAnnotationsEmitter(pw, "src/");
            e.record("a.B", "m", 4, 3, "h", List.of("security"), "", suggestion());
        });
        assertTrue(out.startsWith("::"), "annotation expected: " + out);
        assertFalse(out.contains("\r"));
        assertTrue(out.endsWith("\n"));
    }

    @Test
    void credentialCsv_usesLfOnlyAndForwardSlashes() {
        CredentialCandidate c = new CredentialCandidate("builtin-catalog", "aws-access-key-id",
                CredentialCategory.PROVIDER_TOKEN, 1, 1, 1, 9, "AKIAIOSFODNN7EXAMPLE");
        CredentialFinding f = new CredentialFinding(c, Path.of("src", "FooTest.java"), "FooTest", "m", null,
                null, null);
        String out = capture(pw -> new CredentialCsvEmitter(false).flush(pw, List.of(f)));
        assertFalse(out.contains("\r"));
        assertTrue(out.contains("src/FooTest.java"));
    }

    @Test
    void scoreFormatting_isIndependentOfDefaultLocale() {
        Locale.setDefault(Locale.GERMANY);
        String csv = capture(pw -> {
            OutputEmitter e = new OutputEmitter(pw, true, true, false, false, false, false);
            e.emit(OutputMode.CSV, "a.B", "m", 3, null, List.of(), "", suggestion(), null);
        });
        String plain = capture(pw -> {
            OutputEmitter e = new OutputEmitter(pw, true, true, false, false, false, false);
            e.emit(OutputMode.PLAIN, "a.B", "m", 3, null, List.of(), "", suggestion(), null);
        });
        String github = capture(pw -> new GitHubAnnotationsEmitter(pw, "")
                .record("a.B", "m", 4, 3, "h", List.of("security"), "x",
                        new AiMethodSuggestion("m", true, "n", List.of("security"), "r", 0.5, 0.85)));

        assertTrue(csv.contains(",0.3,0.5,"), "decimal point expected: " + csv);
        assertTrue(plain.contains("AI_INTERACTION_SCORE=0.3, AI_CONFIDENCE=0.5"), plain);
        assertTrue(github.contains("0.9"), github);
        assertFalse(github.contains("0,9"), github);
    }
}
