package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckPromptsCommandTest {

    private static String run(CheckPromptsCommand command, int[] exitOut) throws IOException {
        StringWriter buffer = new StringWriter();
        try (PrintWriter writer = new PrintWriter(buffer)) {
            exitOut[0] = command.execute(writer);
        }
        return buffer.toString();
    }

    @Test
    void builtInDefaultsAllPassAndExitZero() throws IOException {
        int[] exit = new int[1];
        String output = run(new CheckPromptsCommand(Map.of()), exit);
        assertEquals(0, exit[0], "built-in defaults must validate clean");
        assertTrue(output.contains("All prompt templates are valid."), output);
        assertTrue(output.contains("CLASSIFICATION [built-in default]: PASS"), output);
        assertTrue(output.contains("sha256: "), "checksums are printed for auditors");
    }

    @Test
    void invalidOverrideFailsAndExitsOne(@TempDir Path tempDir) throws IOException {
        Path bad = tempDir.resolve("bad.txt");
        Files.writeString(bad, "this template has no placeholders and no anchor", StandardCharsets.UTF_8);

        int[] exit = new int[1];
        String output = run(
                CheckPromptsCommand.fromArgs(new String[] {
                        CheckPromptsCommand.FLAG_CLASSIFICATION_PROMPT, bad.toString() }),
                exit);

        assertEquals(1, exit[0], "an invalid override must fail the check");
        assertTrue(output.contains("CLASSIFICATION [" + bad + "]: FAIL"), output);
        assertTrue(output.contains("One or more prompt templates are INVALID."), output);
    }

    @Test
    void missingOverrideFileIsReportedAsFailure(@TempDir Path tempDir) throws IOException {
        Path missing = tempDir.resolve("does-not-exist.txt");
        int[] exit = new int[1];
        String output = run(
                CheckPromptsCommand.fromArgs(new String[] {
                        CheckPromptsCommand.FLAG_DEDICATED_TRIAGE_PROMPT, missing.toString() }),
                exit);
        assertEquals(1, exit[0], "an unreadable override must fail the check");
        assertTrue(output.contains("cannot read file"), output);
    }
}
