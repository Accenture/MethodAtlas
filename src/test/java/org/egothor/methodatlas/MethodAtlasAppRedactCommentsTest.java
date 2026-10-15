package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.command.ApplyTagsCommand;
import org.egothor.methodatlas.command.CommentRedaction;
import org.egothor.methodatlas.command.PluginLoader;
import org.egothor.methodatlas.command.RedactionAbortedException;
import org.egothor.methodatlas.command.ScanOrchestrator;
import org.egothor.methodatlas.emit.ClassificationOverride;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

/**
 * Stage-1 tests for {@code -redact-comments}: flag and YAML plumbing,
 * fail-closed aborts through the real application entry point, and proof that
 * source write-back still reads the original file.
 *
 * <p>
* Java is redactable (stage 2), so the end-to-end fail-closed tests use ABAP,
 * which has no redactor by design; Java is used for the success and write-back
 * integrity tests with the real {@code JavaCommentRedactor}.
 * </p>
 */
class MethodAtlasAppRedactCommentsTest {

    private static final String MARKER = "TOP-SECRET-COMMENT";

    private static final String LOGIN_TEST_SOURCE = """
            package com.example;

            import org.junit.jupiter.api.Test;

            public class LoginTest {

                // TOP-SECRET-COMMENT
                @Test
                void testLogin() { }
            }
            """;

    private static final String ABAP_TEST_SOURCE = String.join("\n",
            "CLASS zcl_auth_test DEFINITION PUBLIC FINAL FOR TESTING RISK LEVEL HARMLESS DURATION SHORT.",
            "  PUBLIC SECTION.",
            "    METHODS test_login FOR TESTING.",
            "ENDCLASS.",
            "CLASS zcl_auth_test IMPLEMENTATION.",
            "  METHOD test_login.",
            "    \" TOP-SECRET-COMMENT",
            "    DATA lv_result TYPE abap_bool.",
            "  ENDMETHOD.",
            "ENDCLASS.",
            "");

    // -- CLI / YAML plumbing --------------------------------------------------

    @Test
    void cli_redactCommentsFlag_setsAiOption() {
        assertTrue(CliArgs.parse("-ai", "-redact-comments").aiOptions().redactComments());
    }

    @Test
    void cli_aliasFlag_setsAiOption() {
        assertTrue(CliArgs.parse("-ai", "-ai-redact-comments").aiOptions().redactComments());
    }

    @Test
    void cli_default_isOff() {
        assertFalse(CliArgs.parse("-ai").aiOptions().redactComments());
    }

    @Test
    void yaml_redactComments_setsAiOption(@TempDir Path dir) throws IOException {
        Path config = writeYaml(dir, "ai:\n  enabled: true\n  redactComments: true\n");

        assertTrue(CliArgs.parse("-config", config.toString()).aiOptions().redactComments());
    }

    @Test
    void yamlFalse_cliFlagWins(@TempDir Path dir) throws IOException {
        Path config = writeYaml(dir, "ai:\n  enabled: true\n  redactComments: false\n");

        assertTrue(CliArgs.parse("-config", config.toString(), "-redact-comments").aiOptions().redactComments());
        assertFalse(CliArgs.parse("-config", config.toString()).aiOptions().redactComments());
    }

    // -- fail-closed through the application ----------------------------------

    @Test
    void scan_withRedactAndNoRedactorForLanguage_abortsAndNeverCallsProvider(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("zcl_auth_test.abap"), ABAP_TEST_SOURCE, StandardCharsets.UTF_8);
        int exit;
        String err;
        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class)) {
            ErrCapture capture = new ErrCapture();
            exit = runApp("-ai", "-redact-comments", dir.toString());
            err = capture.finish();

            assertFalse(mocked.constructed().isEmpty(), "the engine must exist for the assertion to mean anything");
            for (AiSuggestionEngineImpl engine : mocked.constructed()) {
                verify(engine, never()).suggestForClass(anyString(), anyString(), anyString(), any());
                verify(engine, never()).suggestForClass(anyString(), anyString(), anyString(), any(), any());
                verify(engine, never()).triageSecrets(anyString(), anyString(), any());
            }
        }

        assertNotEquals(0, exit);
        assertTrue(err.contains("zcl_auth_test.abap") && err.contains("*.abap")
                && err.contains("requires a redactor for every scanned language"), err);
    }

    @Test
    void manualPrepare_withRedactAndNoRedactorForLanguage_abortsAndWritesNothing(@TempDir Path dir) throws Exception {
        Path sourceDir = dir.resolve("src");
        Path workDir = dir.resolve("work");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("zcl_auth_test.abap"), ABAP_TEST_SOURCE, StandardCharsets.UTF_8);

        ErrCapture capture = new ErrCapture();
        int exit = runApp("-manual-prepare", workDir.toString(), workDir.toString(), "-redact-comments",
                sourceDir.toString());
        capture.finish();

        assertNotEquals(0, exit);
        try (Stream<Path> files = Files.exists(workDir) ? Files.list(workDir) : Stream.empty()) {
            assertEquals(List.of(), files.toList(), "no work file may be written when redaction aborts");
        }
    }

    @Test
    void manualPrepare_withoutRedact_stillWritesWorkFile(@TempDir Path dir) throws Exception {
        Path sourceDir = dir.resolve("src");
        Path workDir = dir.resolve("work");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("LoginTest.java"), LOGIN_TEST_SOURCE, StandardCharsets.UTF_8);

        int exit = runApp("-manual-prepare", workDir.toString(), workDir.toString(), sourceDir.toString());

        assertEquals(0, exit);
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(workDir)) {
            for (Path p : files.toList()) {
                all.append(Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        assertTrue(all.toString().contains(MARKER), "without the flag the prompt carries comments as today");
    }

    // -- write-back reads the original file -----------------------------------

    private static final String PII_SOURCE = """
            package com.example;

            import org.junit.jupiter.api.Test;

            /**
             * Login tests.
             * @author jane.doe@acme.com, +44 7700 900123
             */
            public class PiiLoginTest {

                // customer 4111-1111-1111-1111 reported this
                @Test
                void testLogin() { // call PII-INLINE-NOTE
                    String url = "http://x /* kept */";
                }

                /* PII-BLOCK-NOTE */
                @Test
                void testLogout() { }
            }
            """;

    private static final List<String> PII = List.of("jane.doe@acme.com", "7700 900123", "4111-1111-1111-1111",
            "PII-INLINE-NOTE", "PII-BLOCK-NOTE", "Login tests");

    private record ApplyResult(int exit, List<String> payloads, String onDisk) { }

    private static ApplyResult applyTags(Path dir, boolean redact) throws Exception {
        Path src = dir.resolve("PiiLoginTest.java");
        Files.writeString(src, PII_SOURCE, StandardCharsets.UTF_8);
        CliConfig cliConfig = redact
                ? CliArgs.parse("-ai", "-redact-comments", "-apply-tags", dir.toString())
                : CliArgs.parse("-ai", "-apply-tags", dir.toString());
        TestDiscoveryConfig discoveryConfig = new TestDiscoveryConfig(cliConfig.fileSuffixes(),
                cliConfig.testMarkers(), cliConfig.properties());
        PluginLoader loader = new PluginLoader();
        CommentRedaction redaction = redact ? new CommentRedaction(loader.loadRedactors(discoveryConfig))
                : CommentRedaction.none();
        ScanOrchestrator orchestrator = new ScanOrchestrator(loader, Optional.empty(), false, redaction);
        RecordingEngine engine = new RecordingEngine();
        int exit;
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(),
                StandardCharsets.UTF_8), true)) {
            exit = new ApplyTagsCommand(cliConfig, discoveryConfig, engine, ClassificationOverride.empty(),
                    AiResultCache.empty(), loader, orchestrator).execute(out);
        }
        return new ApplyResult(exit, engine.payloads, Files.readString(src, StandardCharsets.UTF_8));
    }

    private static List<Integer> annotationLines(String source) {
        List<String> lines = source.lines().toList();
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("@Tag(") || lines.get(i).contains("@DisplayName(")) {
                hits.add(i + 1);
            }
        }
        return hits;
    }

    @Test
    void applyTags_withRealJavaRedactor_sendsBlankedSourceButWritesBackOntoOriginal(@TempDir Path dir)
            throws Exception {
        ApplyResult plain = applyTags(Files.createDirectory(dir.resolve("plain")), false);
        ApplyResult redacted = applyTags(Files.createDirectory(dir.resolve("redacted")), true);

        assertEquals(0, plain.exit());
        assertEquals(0, redacted.exit());
        assertEquals(1, redacted.payloads().size());
        String payload = redacted.payloads().get(0);
        String control = plain.payloads().get(0);
        for (String pii : PII) {
            assertTrue(control.contains(pii), "control run must carry " + pii);
            assertFalse(payload.contains(pii), "provider payload leaked: " + pii);
        }
        assertEquals(control.length(), payload.length(), "redaction must preserve length");
        assertEquals(control.lines().count(), payload.lines().count());
        assertTrue(payload.contains("\"http://x /* kept */\""), "string literal must survive");
        assertTrue(payload.contains("void testLogin()"));

        for (String pii : PII) {
            assertTrue(redacted.onDisk().contains(pii), "original comment lost on disk: " + pii);
        }
        assertFalse(annotationLines(redacted.onDisk()).isEmpty(), redacted.onDisk());
        assertEquals(annotationLines(plain.onDisk()), annotationLines(redacted.onDisk()),
                "@Tag/@DisplayName must land on the same lines as a non-redacted run");
        assertEquals(plain.onDisk(), redacted.onDisk(),
                "write-back must be byte-identical with and without redaction");
    }

    @Test
    void scan_withRealJavaRedactor_succeedsAndProviderSeesNoComments(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("LoginTest.java"), LOGIN_TEST_SOURCE, StandardCharsets.UTF_8);
        List<String> payloads = new ArrayList<>();
        AiClassSuggestion none = new AiClassSuggestion(null, null, null, null,
                List.of(new AiMethodSuggestion("testLogin", false, null, List.of(), null, 0.0, 0.0)));

        int exit;
        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> org.mockito.Mockito.when(mock.suggestForClass(anyString(), anyString(), anyString(), any()))
                        .thenAnswer(inv -> {
                            payloads.add(inv.getArgument(2));
                            return none;
                        }))) {
            exit = runApp("-ai", "-redact-comments", dir.toString());
        }

        assertEquals(0, exit);
        assertEquals(1, payloads.size());
        assertFalse(payloads.get(0).contains(MARKER));
        assertTrue(payloads.get(0).contains("void testLogin()"));
    }

    @Test
    void discoveryOutput_isIdenticalWithAndWithoutRedaction(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("PiiLoginTest.java"), PII_SOURCE, StandardCharsets.UTF_8);
        AiClassSuggestion fixed = new AiClassSuggestion(null, null, null, null, List.of(
                new AiMethodSuggestion("testLogin", false, null, List.of(), null, 0.0, 0.0),
                new AiMethodSuggestion("testLogout", false, null, List.of(), null, 0.0, 0.0)));

        String plain = scanCsv(dir, fixed, false);
        String redacted = scanCsv(dir, fixed, true);

        assertTrue(plain.contains("testLogin") && plain.contains("testLogout"), plain);
        assertEquals(plain, redacted, "method list and line numbers must not depend on redaction");
    }

    private static String scanCsv(Path dir, AiClassSuggestion answer, boolean redact) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> org.mockito.Mockito.when(mock.suggestForClass(anyString(), anyString(), anyString(), any()))
                        .thenReturn(answer));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8), true)) {
            int exit = redact ? MethodAtlasApp.run(new String[] { "-ai", "-redact-comments", dir.toString() }, out)
                    : MethodAtlasApp.run(new String[] { "-ai", dir.toString() }, out);
            assertEquals(0, exit);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void applyTags_withRedactionAndNoRedactorInjected_abortsBeforeProviderAndLeavesFileUntouched(@TempDir Path dir)
            throws Exception {
        Path src = dir.resolve("LoginTest.java");
        Files.writeString(src, LOGIN_TEST_SOURCE, StandardCharsets.UTF_8);
        CliConfig cliConfig = CliArgs.parse("-ai", "-redact-comments", "-apply-tags", dir.toString());
        TestDiscoveryConfig discoveryConfig = new TestDiscoveryConfig(cliConfig.fileSuffixes(),
                cliConfig.testMarkers(), cliConfig.properties());
        PluginLoader loader = new PluginLoader();
        RecordingEngine engine = new RecordingEngine();
        ApplyTagsCommand command = new ApplyTagsCommand(cliConfig, discoveryConfig, engine,
                ClassificationOverride.empty(), AiResultCache.empty(), loader, new ScanOrchestrator(loader, Optional.empty(), false, CommentRedaction.none()));

        try (PrintWriter out = new PrintWriter(new ByteArrayOutputStream(), true)) {
            org.junit.jupiter.api.Assertions.assertThrows(RedactionAbortedException.class,
                    () -> command.execute(out));
        }

        assertEquals(0, engine.payloads.size());
        assertEquals(LOGIN_TEST_SOURCE, Files.readString(src, StandardCharsets.UTF_8));
    }

    // -- regression: flag off is unchanged ------------------------------------

    @Test
    void scan_withoutRedact_providerReceivesOriginalSource(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("LoginTest.java"), LOGIN_TEST_SOURCE, StandardCharsets.UTF_8);
        List<String> payloads = new ArrayList<>();
        AiClassSuggestion none = new AiClassSuggestion(null, null, null, null,
                List.of(new AiMethodSuggestion("testLogin", false, null, List.of(), null, 0.0, 0.0)));

        try (MockedConstruction<AiSuggestionEngineImpl> mocked = mockConstruction(AiSuggestionEngineImpl.class,
                (mock, ctx) -> org.mockito.Mockito.when(mock.suggestForClass(anyString(), anyString(), anyString(), any()))
                        .thenAnswer(inv -> {
                            payloads.add(inv.getArgument(2));
                            return none;
                        }))) {
            int exit = runApp("-ai", dir.toString());
            assertEquals(0, exit);
        }

        assertEquals(1, payloads.size());
        assertTrue(payloads.get(0).contains("// " + MARKER), "comments must be sent verbatim when redaction is off");
    }

    // -- helpers --------------------------------------------------------------

    private static Path writeYaml(Path dir, String yaml) throws IOException {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }

    private static int runApp(String... args) throws Exception {
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new ByteArrayOutputStream(),
                StandardCharsets.UTF_8), true)) {
            return MethodAtlasApp.run(args, out);
        }
    }

    /** Temporarily redirects {@code System.err} so the abort message can be asserted. */
    private static final class ErrCapture {
        private final PrintStream original = System.err;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        ErrCapture() {
            System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        }

        String finish() {
            System.setErr(original);
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class RecordingEngine implements AiSuggestionEngine {
        private final List<String> payloads = new ArrayList<>();

        @Override
        public AiClassSuggestion suggestForClass(String fileStem, String fqcn, String classSource,
                List<PromptBuilder.TargetMethod> targetMethods) {
            payloads.add(classSource);
            List<AiMethodSuggestion> methods = targetMethods.stream()
                    .map(t -> new AiMethodSuggestion(t.methodName(), true, "Checks " + t.methodName(),
                            List.of("security"), null, 0.9, 0.0))
                    .toList();
            return new AiClassSuggestion(null, null, null, null, methods);
        }
    }
}
