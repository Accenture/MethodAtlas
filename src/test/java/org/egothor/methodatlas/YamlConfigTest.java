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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlConfigTest {

    @Test
    void load_parsesOutputMode(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "outputMode: sarif\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertEquals("sarif", cfg.outputMode);
    }

    @Test
    void load_parsesEmitMetadata(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "emitMetadata: true\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertTrue(cfg.emitMetadata);
    }

    @Test
    void load_parsesFileSuffixes(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "fileSuffixes:\n  - Test.java\n  - IT.java\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertEquals(List.of("Test.java", "IT.java"), cfg.fileSuffixes);
    }

    @Test
    void load_parsesTestAnnotations(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "testAnnotations:\n  - Test\n  - ParameterizedTest\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertEquals(List.of("Test", "ParameterizedTest"), cfg.testAnnotations);
    }

    @Test
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
    void load_allFieldsNullByDefault(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "{}\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);

        assertNull(cfg.outputMode);
        assertNull(cfg.fileSuffixes);
        assertNull(cfg.testAnnotations);
        assertNull(cfg.ai);
    }

    @Test
    void load_ignoresUnknownTopLevelFields(@TempDir Path tempDir) throws Exception {
        Path config = write(tempDir, "unknownField: someValue\noutputMode: csv\n");
        YamlConfig.YamlConfigFile cfg = YamlConfig.load(config);
        assertEquals("csv", cfg.outputMode);
    }

    @Test
    void load_throwsIoExceptionForMissingFile(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("nonexistent.yaml");
        assertThrows(IOException.class, () -> YamlConfig.load(missing));
    }

    @Test
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

    private static Path write(Path dir, String content) throws IOException {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
