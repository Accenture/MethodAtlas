package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration tests for SARIF output mode and YAML config file loading.
 */
public class MethodAtlasAppSarifTest {

    // -------------------------------------------------------------------------
    // SARIF output mode
    // -------------------------------------------------------------------------

    @Test
    void sarifMode_emitsValidSarifDocument(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        String output = runApp(new String[] { "-sarif", sourceDir.toString() });

        JsonNode doc = new ObjectMapper().readTree(output);
        assertEquals("2.1.0", doc.path("version").asText());
        assertTrue(doc.path("$schema").asText().contains("sarif"));
        assertEquals(1, doc.path("runs").size());
    }

    @Test
    void sarifMode_emitsOneResultPerTestMethod(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        // -include-non-security: structural test — result count is independent of AI classification
        String output = runApp(new String[] { "-sarif", "-include-non-security", sourceDir.toString() });
        JsonNode results = new ObjectMapper().readTree(output).path("runs").get(0).path("results");

        assertTrue(results.isArray());
        assertTrue(results.size() > 0, "Should have at least one result");
    }

    @Test
    void sarifMode_resultHasPhysicalAndLogicalLocation(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        // -include-non-security: structural test — location presence is independent of AI classification
        String output = runApp(new String[] { "-sarif", "-include-non-security", sourceDir.toString() });
        JsonNode result = new ObjectMapper().readTree(output)
                .path("runs").get(0).path("results").get(0);

        JsonNode physLoc = result.path("locations").get(0).path("physicalLocation");
        assertFalse(physLoc.path("artifactLocation").path("uri").asText().isEmpty(),
                "artifactLocation.uri should not be empty");

        JsonNode logLoc = result.path("locations").get(0).path("logicalLocations").get(0);
        assertTrue(logLoc.path("fullyQualifiedName").asText().contains("shouldAllowOwnerToReadOwnStatement")
                || logLoc.path("fullyQualifiedName").asText().contains("AccessControlServiceTest"),
                "logicalLocation should reference the test method or class");
    }

    @Test
    void sarifMode_outputIsNotCsvHeader(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        String output = runApp(new String[] { "-sarif", sourceDir.toString() });

        assertFalse(output.contains("fqcn,method,loc,tags"),
                "SARIF output should not contain a CSV header");
        assertTrue(output.trim().startsWith("{"), "SARIF output should start with {");
    }

    @Test
    void sarifMode_allMethodsUseLevelNone_whenAiDisabled(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        // -include-non-security: level=none is a structural property independent of AI classification
        String output = runApp(new String[] { "-sarif", "-include-non-security", sourceDir.toString() });
        JsonNode results = new ObjectMapper().readTree(output).path("runs").get(0).path("results");

        assertTrue(results.size() > 0, "Expected results to verify level field");
        for (JsonNode result : results) {
            assertEquals("none", result.path("level").asText(),
                    "Without AI, all results should have level 'none'");
        }
    }

    @Test
    void sarifMode_toolDriverNameIsMethodAtlas(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        String output = runApp(new String[] { "-sarif", sourceDir.toString() });
        JsonNode driver = new ObjectMapper().readTree(output)
                .path("runs").get(0).path("tool").path("driver");

        assertEquals("MethodAtlas", driver.path("name").asText());
    }

    // -------------------------------------------------------------------------
    // YAML config
    // -------------------------------------------------------------------------

    @Test
    void configFile_overridesOutputMode_toPlain(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "outputMode: plain\n", StandardCharsets.UTF_8);

        String output = runApp(new String[] { "-config", configFile.toString(), sourceDir.toString() });

        assertFalse(output.contains("fqcn,method,loc,tags"),
                "Plain mode should not emit CSV header");
        assertTrue(output.contains("LOC="), "Plain mode should emit LOC= tokens");
    }

    @Test
    void configFile_overridesOutputMode_toSarif(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "outputMode: sarif\n", StandardCharsets.UTF_8);

        String output = runApp(new String[] { "-config", configFile.toString(), sourceDir.toString() });

        assertFalse(output.contains("fqcn,method,loc,tags"),
                "SARIF mode should not emit CSV header");
        assertTrue(output.trim().startsWith("{"), "SARIF output should be JSON");
    }

    @Test
    void configFile_overridesFileSuffixes(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        // Copy a fixture but rename it so it only matches a custom suffix
        copyFixture(sourceDir, "AccessControlServiceTest.java");
        // Also create a file with custom suffix
        Files.writeString(sourceDir.resolve("SomeSpec.java"),
                "package com.acme;\nimport org.junit.jupiter.api.Test;\nclass SomeSpec {\n@Test void specTest() {}\n}\n",
                StandardCharsets.UTF_8);

        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "fileSuffixes:\n  - Spec.java\n", StandardCharsets.UTF_8);

        String output = runApp(new String[] { "-config", configFile.toString(), sourceDir.toString() });

        List<String> lines = nonEmptyLines(output);
        // Only Spec.java file should be scanned — the FQCN should be SomeSpec
        assertTrue(lines.stream().anyMatch(l -> l.contains("SomeSpec")),
                "Should detect SomeSpec");
        assertFalse(lines.stream().anyMatch(l -> l.contains("AccessControlServiceTest")),
                "AccessControlServiceTest should not be scanned with Spec.java suffix");
    }

    @Test
    void cliFlag_overridesConfigFileOutputMode(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        // Config says plain, but CLI says CSV (default = CSV when no -plain/-sarif)
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "outputMode: plain\n", StandardCharsets.UTF_8);

        // Explicitly pass -sarif on CLI to override YAML plain
        String output = runApp(new String[] { "-config", configFile.toString(), "-sarif", sourceDir.toString() });

        assertTrue(output.trim().startsWith("{"), "CLI -sarif should override YAML outputMode:plain");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void copyFixture(Path destDir, String fixtureFileName) throws IOException {
        String resourcePath = "/fixtures/" + fixtureFileName + ".txt";
        try (InputStream in = MethodAtlasAppSarifTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing test resource: " + resourcePath);
            Files.copy(in, destDir.resolve(fixtureFileName));
        }
    }

    private static String runApp(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(args, out);
        }
        return baos.toString(StandardCharsets.UTF_8);
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
