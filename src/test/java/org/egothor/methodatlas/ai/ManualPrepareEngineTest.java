package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualPrepareEngineTest {

    @Test
    void prepare_createsWorkFileWithOperatorInstructionsAndPrompt(@TempDir Path workDir) throws Exception {
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, options);

        String fqcn = "com.acme.security.AccessControlServiceTest";
        String classSource = "class AccessControlServiceTest { @Test void shouldAllowOwner() {} }";
        List<PromptBuilder.TargetMethod> targetMethods = List.of(
                new PromptBuilder.TargetMethod("shouldAllowOwner", 1, 1));

        Path workFile = engine.prepare(fqcn, classSource, targetMethods);

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
    void prepare_createsWorkDirectoryIfAbsent(@TempDir Path tempDir) throws Exception {
        Path workDir = tempDir.resolve("nested/workdir");
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, options);

        engine.prepare("com.acme.FooTest", "class FooTest {}",
                List.of(new PromptBuilder.TargetMethod("testFoo", 1, 1)));

        assertTrue(Files.isDirectory(workDir), "Work directory should be created");
        assertTrue(Files.exists(workDir.resolve("com.acme.FooTest.txt")), "Work file should exist");
    }

    @Test
    void prepare_usesExternalTaxonomyFileWhenConfigured(@TempDir Path tempDir) throws Exception {
        Path taxonomyFile = tempDir.resolve("taxonomy.txt");
        Files.writeString(taxonomyFile, "custom-taxonomy-content", StandardCharsets.UTF_8);

        AiOptions options = AiOptions.builder().taxonomyFile(taxonomyFile).build();
        ManualPrepareEngine engine = new ManualPrepareEngine(tempDir.resolve("work"), options);

        engine.prepare("com.acme.FooTest", "class FooTest {}",
                List.of(new PromptBuilder.TargetMethod("testFoo", 1, 1)));

        String content = Files.readString(tempDir.resolve("work/com.acme.FooTest.txt"), StandardCharsets.UTF_8);
        assertTrue(content.contains("custom-taxonomy-content"), "Prompt should contain custom taxonomy");
    }

    @Test
    void prepare_returnsPathOfWrittenFile(@TempDir Path workDir) throws Exception {
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, options);

        Path result = engine.prepare("org.example.BarTest", "class BarTest {}",
                List.of(new PromptBuilder.TargetMethod("testBar", 5, 10)));

        assertEquals(workDir.resolve("org.example.BarTest.txt"), result);
    }

    @Test
    void prepare_writesMultipleWorkFilesForDifferentClasses(@TempDir Path workDir) throws Exception {
        AiOptions options = AiOptions.builder().build();
        ManualPrepareEngine engine = new ManualPrepareEngine(workDir, options);

        engine.prepare("com.acme.FooTest", "class FooTest {}",
                List.of(new PromptBuilder.TargetMethod("testFoo", 1, 1)));
        engine.prepare("com.acme.BarTest", "class BarTest {}",
                List.of(new PromptBuilder.TargetMethod("testBar", 1, 1)));

        assertTrue(Files.exists(workDir.resolve("com.acme.FooTest.txt")));
        assertTrue(Files.exists(workDir.resolve("com.acme.BarTest.txt")));
    }
}
