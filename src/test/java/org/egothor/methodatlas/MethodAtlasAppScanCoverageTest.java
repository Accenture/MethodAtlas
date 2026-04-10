package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

/**
 * Focused branch-coverage tests for {@link MethodAtlasApp} paths that are not
 * reached by the broader integration tests:
 *
 * <ul>
 * <li>{@code processFile} – parse-failure path (return {@code false}) and the
 *     no-package-declaration branch ({@code packageName = ""})</li>
 * <li>{@code processFileForPrepare} – parse-failure path (return {@code -1})
 *     and the empty-test-method-list branch ({@code continue})</li>
 * <li>{@code resolveTaxonomyInfo} – all three arms: AI disabled (existing
 *     coverage), built-in default/optimized taxonomy, and external taxonomy
 *     file</li>
 * </ul>
 */
@Tag("unit")
@Tag("method-atlas-app")
class MethodAtlasAppScanCoverageTest {

    // -------------------------------------------------------------------------
    // processFile – parse failure
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CSV scan with unparseable source file returns exit code 1")
    @Tag("negative")
    void csvMode_unparseableFile_returnsExitCode1(@TempDir Path tempDir) throws Exception {
        // Deliberately broken Java syntax – JavaParser cannot produce a successful parse result.
        Files.writeString(tempDir.resolve("BrokenTest.java"),
                "this {{ is @@@@ NOT valid Java ###",
                StandardCharsets.UTF_8);

        assertEquals(1, runAppExitCode(new String[] { tempDir.toString() }),
                "Exit code must be 1 when a source file cannot be parsed");
    }

    // -------------------------------------------------------------------------
    // processFile – no package declaration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Source file without package declaration uses simple class name as FQCN")
    @Tag("positive")
    void csvMode_noPackageDeclaration_usesSimpleClassNameAsFqcn(@TempDir Path tempDir) throws Exception {
        // No 'package' statement → packageName="" → buildFqcn returns the simple class name.
        Files.writeString(tempDir.resolve("NoPackageTest.java"), """
                import org.junit.jupiter.api.Test;
                class NoPackageTest {
                    @Test
                    void testMethod() {}
                }
                """, StandardCharsets.UTF_8);

        String output = runApp(new String[] { tempDir.toString() });
        List<String> lines = nonEmptyLines(output);

        // header + 1 data row
        assertEquals(2, lines.size(), "Expected CSV header + 1 data row");
        assertTrue(lines.get(1).startsWith("NoPackageTest,"),
                "FQCN must be the bare class name (no leading dot): " + lines.get(1));
    }

    // -------------------------------------------------------------------------
    // processFileForPrepare – parse failure
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("manual-prepare with unparseable source file returns exit code 1")
    @Tag("negative")
    void manualPrepare_unparseableFile_returnsExitCode1(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path workDir   = tempDir.resolve("work");
        Files.createDirectories(sourceDir);

        Files.writeString(sourceDir.resolve("BrokenTest.java"),
                "this {{ is @@@@ NOT valid Java ###",
                StandardCharsets.UTF_8);

        assertEquals(1, runAppExitCode(new String[] {
                "-manual-prepare", workDir.toString(), workDir.toString(), sourceDir.toString() }),
                "Manual-prepare must return exit code 1 when a source file cannot be parsed");
    }

    // -------------------------------------------------------------------------
    // processFileForPrepare – class with no test methods
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("manual-prepare skips classes that have no test methods and reports 0 work files")
    @Tag("positive")
    void manualPrepare_classWithNoTestMethods_writesNoWorkFiles(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path workDir   = tempDir.resolve("work");
        Files.createDirectories(sourceDir);
        Files.createDirectories(workDir);

        // File whose suffix matches ("Test.java") but contains no @Test-annotated methods.
        // This exercises the testMethods.isEmpty() → continue branch.
        Files.writeString(sourceDir.resolve("HelperTest.java"), """
                package com.example;
                public class HelperTest {
                    public void helper()     {}
                    public void anotherOne() {}
                }
                """, StandardCharsets.UTF_8);

        String output = runApp(new String[] {
                "-manual-prepare", workDir.toString(), workDir.toString(), sourceDir.toString() });

        assertTrue(output.contains("Wrote 0 work file"),
                "Summary must report 0 work files when no test methods are found: " + output);
    }

    // -------------------------------------------------------------------------
    // resolveTaxonomyInfo – AI enabled, built-in default taxonomy
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-emit-metadata with AI enabled emits 'built-in/default' taxonomy info")
    @Tag("positive")
    void emitMetadata_aiEnabled_emitsBuiltinDefaultTaxonomy(@TempDir Path tempDir) throws Exception {
        // mockConstruction intercepts new AiSuggestionEngineImpl(…) so the real
        // constructor (which requires a live API key) is never called.
        try (MockedConstruction<AiSuggestionEngineImpl> mocked =
                mockConstruction(AiSuggestionEngineImpl.class)) {

            String output = runApp(new String[] { "-emit-metadata", "-ai", tempDir.toString() });

            assertEquals(1, mocked.constructed().size(), "AI engine must be constructed exactly once");
            assertTrue(output.contains("# taxonomy: built-in/default"),
                    "Metadata must report 'built-in/default' when AI is enabled without a taxonomy file");
        }
    }

    // -------------------------------------------------------------------------
    // resolveTaxonomyInfo – AI enabled, built-in optimized taxonomy
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-emit-metadata with -ai-taxonomy-mode optimized emits 'built-in/optimized' taxonomy info")
    @Tag("positive")
    void emitMetadata_aiEnabled_optimizedMode_emitsBuiltinOptimized(@TempDir Path tempDir) throws Exception {
        try (MockedConstruction<AiSuggestionEngineImpl> mocked =
                mockConstruction(AiSuggestionEngineImpl.class)) {

            String output = runApp(new String[] {
                    "-emit-metadata", "-ai", "-ai-taxonomy-mode", "optimized", tempDir.toString() });

            assertEquals(1, mocked.constructed().size(), "AI engine must be constructed exactly once");
            assertTrue(output.contains("# taxonomy: built-in/optimized"),
                    "Metadata must report 'built-in/optimized' when that taxonomy mode is selected");
        }
    }

    // -------------------------------------------------------------------------
    // resolveTaxonomyInfo – AI enabled, external taxonomy file
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-emit-metadata with -ai-taxonomy file path emits 'file:' prefix in taxonomy info")
    @Tag("positive")
    void emitMetadata_aiEnabled_taxonomyFile_emitsFilePath(@TempDir Path tempDir) throws Exception {
        Path taxonomyFile = tempDir.resolve("custom-taxonomy.txt");
        Files.writeString(taxonomyFile, "Security\nAuthentication\n", StandardCharsets.UTF_8);

        try (MockedConstruction<AiSuggestionEngineImpl> mocked =
                mockConstruction(AiSuggestionEngineImpl.class)) {

            String output = runApp(new String[] {
                    "-emit-metadata", "-ai",
                    "-ai-taxonomy", taxonomyFile.toString(),
                    tempDir.toString() });

            assertEquals(1, mocked.constructed().size(), "AI engine must be constructed exactly once");
            assertTrue(output.contains("# taxonomy: file:"),
                    "Metadata must start taxonomy info with 'file:' when a taxonomy file is provided");
            assertTrue(output.contains("custom-taxonomy.txt"),
                    "Taxonomy metadata must contain the taxonomy file name");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String runApp(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(args, out);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static int runAppExitCode(String[] args) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            return MethodAtlasApp.run(args, out);
        }
    }

    private static List<String> nonEmptyLines(String text) {
        String[] parts = text.split("\\R");
        List<String> lines = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }
}
