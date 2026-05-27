package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ManualPrepareEngine}.
 *
 * <p>
 * This class verifies that the prepare engine writes work files with the
 * expected operator instructions and AI prompt sections, creates required
 * directories automatically, places empty response stubs in the response
 * directory, does not overwrite existing response files, and correctly handles
 * shared work/response directories and external taxonomy files.
 * </p>
 */
@Tag("unit")
@Tag("manual-prepare")
class ManualPrepareEngineTest {

    @Test
    @DisplayName("prepare creates work file with operator instructions and embedded AI prompt")
    @Tag("positive")
    void prepare_createsWorkFileWithOperatorInstructionsAndPrompt(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("work");
        Path responseDir = tempDir.resolve("responses");
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, responseDir, options);

        String fqcn = "com.acme.security.AccessControlServiceTest";
        String classSource = "class AccessControlServiceTest { @Test void shouldAllowOwner() {} }";
        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldAllowOwner", 1, 1));

        Path workFile = engine.prepare(fqcn, fqcn, classSource, targetMethods);

        assertNotNull(workFile);
        assertEquals(workDir.resolve(fqcn + ".txt"), workFile);
        assertTrue(Files.exists(workFile), "Work file should be created");

        String content = Files.readString(workFile, StandardCharsets.UTF_8);

        // Operator instructions section
        assertTrue(content.contains("OPERATOR INSTRUCTIONS"), "Should contain operator instructions header");
        assertTrue(content.contains(fqcn), "Should contain FQCN in instructions");
        assertTrue(content.contains(fqcn + ".response.txt"), "Should contain expected response file name");
        assertTrue(content.contains("-manual-consume"), "Should reference consume phase command");

        // AI prompt section
        assertTrue(content.contains("--- BEGIN AI PROMPT ---"), "Should contain begin marker");
        assertTrue(content.contains("--- END AI PROMPT ---"), "Should contain end marker");
        assertTrue(content.contains("FQCN: " + fqcn), "Prompt should contain FQCN");
        assertTrue(content.contains("shouldAllowOwner"), "Prompt should contain method name");
        assertTrue(content.contains("AccessControlServiceTest"), "Prompt should contain class source");
    }

    @Test
    @DisplayName("prepare creates the work directory if it does not exist")
    @Tag("positive")
    void prepare_createsWorkDirectoryIfAbsent(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("nested/workdir");
        Path responseDir = tempDir.resolve("nested/responses");
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, responseDir, options);

        engine.prepare("com.acme.FooTest", "com.acme.FooTest", "class FooTest {}",
                List.of(new PromptBuilder.TargetMethod("testFoo", 1, 1)));

        assertTrue(Files.isDirectory(workDir), "Work directory should be created");
        assertTrue(Files.exists(workDir.resolve("com.acme.FooTest.txt")), "Work file should exist");
    }

    @Test
    @DisplayName("prepare creates the response directory if it does not exist")
    @Tag("positive")
    void prepare_createsResponseDirectoryIfAbsent(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("work");
        Path responseDir = tempDir.resolve("nested/responses");
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, responseDir, options);

        engine.prepare("com.acme.FooTest", "com.acme.FooTest", "class FooTest {}",
                List.of(new PromptBuilder.TargetMethod("testFoo", 1, 1)));

        assertTrue(Files.isDirectory(responseDir), "Response directory should be created");
        assertTrue(Files.exists(responseDir.resolve("com.acme.FooTest.response.txt")),
                "Response stub should exist in response directory");
    }

    @Test
    @DisplayName("prepare uses external taxonomy file content in the prompt when configured")
    @Tag("positive")
    void prepare_usesExternalTaxonomyFileWhenConfigured(@TempDir Path tempDir) throws Exception {
        Path taxonomyFile = tempDir.resolve("taxonomy.txt");
        Files.writeString(taxonomyFile, "custom-taxonomy-content", StandardCharsets.UTF_8);

        AiOptions options = AiOptions.builder().taxonomyFile(taxonomyFile).build();
        ManualPrepareEngine engine = new ManualPrepareEngine(
                tempDir.resolve("work"), tempDir.resolve("responses"), options);

        engine.prepare("com.acme.FooTest", "com.acme.FooTest", "class FooTest {}",
                List.of(new PromptBuilder.TargetMethod("testFoo", 1, 1)));

        String content = Files.readString(tempDir.resolve("work/com.acme.FooTest.txt"), StandardCharsets.UTF_8);
        assertTrue(content.contains("custom-taxonomy-content"), "Prompt should contain custom taxonomy");
    }

    @Test
    @DisplayName("prepare returns the path of the written work file")
    @Tag("positive")
    void prepare_returnsPathOfWrittenFile(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("work");
        Path responseDir = tempDir.resolve("responses");
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, responseDir, options);

        Path result = engine.prepare("org.example.BarTest", "org.example.BarTest", "class BarTest {}",
                List.of(new PromptBuilder.TargetMethod("testBar", 5, 10)));

        assertEquals(workDir.resolve("org.example.BarTest.txt"), result);
    }

    @Test
    @DisplayName("prepare writes separate work files for different classes")
    @Tag("positive")
    void prepare_writesMultipleWorkFilesForDifferentClasses(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("work");
        Path responseDir = tempDir.resolve("responses");
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, responseDir, options);

        engine.prepare("com.acme.FooTest", "com.acme.FooTest", "class FooTest {}",
                List.of(new PromptBuilder.TargetMethod("testFoo", 1, 1)));
        engine.prepare("com.acme.BarTest", "com.acme.BarTest", "class BarTest {}",
                List.of(new PromptBuilder.TargetMethod("testBar", 1, 1)));

        assertTrue(Files.exists(workDir.resolve("com.acme.FooTest.txt")));
        assertTrue(Files.exists(workDir.resolve("com.acme.BarTest.txt")));
    }

    @Test
    @DisplayName("prepare pre-creates an empty response stub in the response directory")
    @Tag("positive")
    void prepare_createsEmptyResponseStubInResponseDir(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("work");
        Path responseDir = tempDir.resolve("responses");
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, responseDir, options);

        engine.prepare("com.acme.FooTest", "com.acme.FooTest", "class FooTest {}",
                List.of(new PromptBuilder.TargetMethod("testFoo", 1, 1)));

        Path responseFile = responseDir.resolve("com.acme.FooTest.response.txt");
        assertTrue(Files.exists(responseFile), "Empty response stub should be pre-created in responseDir");
        assertEquals("", Files.readString(responseFile, StandardCharsets.UTF_8),
                "Pre-created response stub should be empty");
    }

    @Test
    @DisplayName("prepare does not overwrite an existing response file")
    @Tag("edge-case")
    void prepare_doesNotOverwriteExistingResponseFile(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("work");
        Path responseDir = tempDir.resolve("responses");
        Files.createDirectories(responseDir);
        Path responseFile = responseDir.resolve("com.acme.FooTest.response.txt");
        Files.writeString(responseFile, "existing content", StandardCharsets.UTF_8);

        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, responseDir, options);
        engine.prepare("com.acme.FooTest", "com.acme.FooTest", "class FooTest {}",
                List.of(new PromptBuilder.TargetMethod("testFoo", 1, 1)));

        assertEquals("existing content", Files.readString(responseFile, StandardCharsets.UTF_8),
                "Existing response file content must not be overwritten");
    }

    @Test
    @DisplayName("prepare works correctly when work and response directories are the same")
    @Tag("edge-case")
    void prepare_supportsSameDirForWorkAndResponse(@TempDir Path sharedDir) throws Exception {
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(sharedDir, sharedDir, options);

        engine.prepare("com.acme.FooTest", "com.acme.FooTest", "class FooTest {}",
                List.of(new PromptBuilder.TargetMethod("testFoo", 1, 1)));

        assertTrue(Files.exists(sharedDir.resolve("com.acme.FooTest.txt")),
                "Work file should be in shared directory");
        assertTrue(Files.exists(sharedDir.resolve("com.acme.FooTest.response.txt")),
                "Response stub should be in shared directory");
    }
}
