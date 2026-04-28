package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link YamlConfig}.
 *
 * <p>
 * This class verifies that YAML configuration files are correctly deserialized
 * into {@link YamlConfig.YamlConfigFile} instances, including all supported
 * top-level and nested AI configuration fields, default null values, and
 * error handling for missing files.
 * </p>
 */
@Tag("unit")
@Tag("yaml-config")
class YamlConfigTest {

    @Test
    @DisplayName("load parses outputMode field from YAML")
    @Tag("positive")
    void load_parsesOutputMode(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "outputMode: sarif\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertEquals("sarif", cfg.outputMode);
    }

    @Test
    @DisplayName("load parses emitMetadata field from YAML")
    @Tag("positive")
    void load_parsesEmitMetadata(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "emitMetadata: true\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertTrue(cfg.emitMetadata);
    }

    @Test
    @DisplayName("load parses fileSuffixes list from YAML")
    @Tag("positive")
    void load_parsesFileSuffixes(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "fileSuffixes:\n  - Test.java\n  - IT.java\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertEquals(List.of("Test.java", "IT.java"), cfg.fileSuffixes);
    }

    @Test
    @DisplayName("load parses testMarkers list from YAML")
    @Tag("positive")
    void load_parsesTestMarkers(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "testMarkers:\n  - Test\n  - ParameterizedTest\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertEquals(List.of("Test", "ParameterizedTest"), cfg.testMarkers);
    }

    @Test
    @DisplayName("load parses properties map from YAML")
    @Tag("positive")
    void load_parsesProperties(@TempDir Path tempDir) throws Exception {
        String yaml = "properties:\n  functionNames:\n    - test\n    - it\n";
        Path config = write(tempDir, yaml);
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertEquals(Map.of("functionNames", List.of("test", "it")), cfg.properties);
    }

    @Test
    @DisplayName("load parses all AI configuration fields from YAML")
    @Tag("positive")
    void load_parsesAiSection(@TempDir Path tempDir) throws Exception {
        String yaml = """
                ai:
                  enabled: true
                  provider: ollama
                  model: qwen2.5-coder:7b
                  taxonomyMode: optimized
                  confidence: true
                  maxClassChars: 50000
                  timeoutSec: 60
                  maxRetries: 5
                """;
        Path config = write(tempDir, yaml);
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);

        assertNotNull(cfg.ai);
        assertTrue(cfg.ai.enabled);
        assertEquals("ollama", cfg.ai.provider);
        assertEquals("qwen2.5-coder:7b", cfg.ai.model);
        assertEquals("optimized", cfg.ai.taxonomyMode);
        assertTrue(cfg.ai.confidence);
        assertEquals(50000, cfg.ai.maxClassChars);
        assertEquals(60L, cfg.ai.timeoutSec);
        assertEquals(5, cfg.ai.maxRetries);
    }

    @Test
    @DisplayName("load returns null for all optional fields when YAML is an empty object")
    @Tag("edge-case")
    void load_allFieldsNullByDefault(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "{}\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);

        assertNull(cfg.outputMode);
        assertNull(cfg.fileSuffixes);
        assertNull(cfg.testMarkers);
        assertNull(cfg.properties);
        assertNull(cfg.ai);
    }

    @Test
    @DisplayName("load ignores unknown top-level fields and parses known ones")
    @Tag("positive")
    void load_ignoresUnknownTopLevelFields(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "unknownField: someValue\noutputMode: csv\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertEquals("csv", cfg.outputMode);
    }

    @Test
    @DisplayName("load throws IOException when config file does not exist")
    @Tag("negative")
    void load_throwsIoExceptionForMissingFile(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("nonexistent.yaml");
        assertThrows(IOException.class, () -> YamlConfig.load(missing));
    }

    @Test
    @DisplayName("load parses AI apiKey, apiKeyEnv, baseUrl, and taxonomyFile fields")
    @Tag("positive")
    void load_parsesApiKeyFields(@TempDir Path tempDir) throws Exception {
        String yaml = """
                ai:
                  apiKey: sk-test-key
                  apiKeyEnv: MY_API_KEY
                  baseUrl: http://custom.host:8080
                  taxonomyFile: /path/to/taxonomy.txt
                """;
        Path config = write(tempDir, yaml);
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);

        assertEquals("sk-test-key", cfg.ai.apiKey);
        assertEquals("MY_API_KEY", cfg.ai.apiKeyEnv);
        assertEquals("http://custom.host:8080", cfg.ai.baseUrl);
        assertEquals("/path/to/taxonomy.txt", cfg.ai.taxonomyFile);
    }

    @Test
    @DisplayName("load parses contentHash: true field correctly")
    @Tag("positive")
    void load_parsesContentHashField(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "contentHash: true\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertTrue(cfg.contentHash);
    }

    private static Path write(Path dir, String content) throws IOException {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
