package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link CliArgs}.
 *
 * <p>
 * These tests verify that command-line arguments are parsed into a correct
 * {@link CliConfig}: output modes, AI options, file-suffix handling,
 * test-annotation overrides, manual modes, positional paths, and error cases
 * for unknown flags and missing values.
 * </p>
 */
@Tag("unit")
@Tag("cli-args")
class CliArgsTest {

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parse with no arguments returns default CliConfig")
    @Tag("positive")
    void parse_noArgs_returnsDefaults() {
        CliConfig cfg = CliArgs.parse();

        assertEquals(OutputMode.CSV, cfg.outputMode());
        assertFalse(cfg.aiOptions().enabled());
        assertEquals(List.of(), cfg.paths());
        assertEquals(List.of("Test.java"), cfg.fileSuffixes());
        assertEquals(AnnotationInspector.DEFAULT_TEST_ANNOTATIONS, cfg.testAnnotations());
        assertFalse(cfg.emitMetadata());
        assertNull(cfg.manualMode());
        assertFalse(cfg.applyTags());
        assertFalse(cfg.contentHash());
        assertNull(cfg.overrideFile());
        assertFalse(cfg.securityOnly());
    }

    // -------------------------------------------------------------------------
    // Output modes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-plain flag sets PLAIN output mode")
    @Tag("positive")
    void parse_plainFlag_setsPlainMode() {
        CliConfig cfg = CliArgs.parse("-plain");
        assertEquals(OutputMode.PLAIN, cfg.outputMode());
    }

    @Test
    @DisplayName("-sarif flag sets SARIF output mode")
    @Tag("positive")
    void parse_sarifFlag_setSarifMode() {
        CliConfig cfg = CliArgs.parse("-sarif");
        assertEquals(OutputMode.SARIF, cfg.outputMode());
    }

    // -------------------------------------------------------------------------
    // Boolean flags
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-apply-tags flag sets applyTags=true")
    @Tag("positive")
    void parse_applyTagsFlag_setsApplyTagsTrue() {
        assertTrue(CliArgs.parse("-apply-tags").applyTags());
    }

    @Test
    @DisplayName("-content-hash flag sets contentHash=true")
    @Tag("positive")
    void parse_contentHashFlag_setsContentHashTrue() {
        assertTrue(CliArgs.parse("-content-hash").contentHash());
    }

    @Test
    @DisplayName("-emit-metadata flag sets emitMetadata=true")
    @Tag("positive")
    void parse_emitMetadataFlag_setsEmitMetadataTrue() {
        assertTrue(CliArgs.parse("-emit-metadata").emitMetadata());
    }

    // -------------------------------------------------------------------------
    // File suffixes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-file-suffix IT.java replaces default suffix")
    @Tag("positive")
    void parse_fileSuffix_replacesDefault() {
        CliConfig cfg = CliArgs.parse("-file-suffix", "IT.java");
        assertEquals(List.of("IT.java"), cfg.fileSuffixes());
    }

    @Test
    @DisplayName("multiple -file-suffix flags collect all and replace default on first occurrence")
    @Tag("positive")
    void parse_multipleFileSuffixes_allCollected() {
        CliConfig cfg = CliArgs.parse("-file-suffix", "IT.java", "-file-suffix", "Spec.java");
        assertEquals(List.of("IT.java", "Spec.java"), cfg.fileSuffixes());
    }

    // -------------------------------------------------------------------------
    // Test annotations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-test-annotation MyTest replaces defaults with {MyTest}")
    @Tag("positive")
    void parse_testAnnotation_replacesDefaults() {
        CliConfig cfg = CliArgs.parse("-test-annotation", "MyTest");
        assertEquals(1, cfg.testAnnotations().size());
        assertTrue(cfg.testAnnotations().contains("MyTest"));
    }

    @Test
    @DisplayName("two -test-annotation flags accumulate both values")
    @Tag("positive")
    void parse_twoTestAnnotations_bothPresent() {
        CliConfig cfg = CliArgs.parse("-test-annotation", "Test", "-test-annotation", "MyTest");
        assertTrue(cfg.testAnnotations().contains("Test"));
        assertTrue(cfg.testAnnotations().contains("MyTest"));
    }

    // -------------------------------------------------------------------------
    // AI flags
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-ai flag sets aiOptions.enabled=true")
    @Tag("positive")
    void parse_aiFlag_setsEnabled() {
        assertTrue(CliArgs.parse("-ai").aiOptions().enabled());
    }

    @Test
    @DisplayName("-ai-confidence flag sets aiOptions.confidence=true")
    @Tag("positive")
    void parse_aiConfidenceFlag_setsConfidence() {
        assertTrue(CliArgs.parse("-ai-confidence").aiOptions().confidence());
    }

    @Test
    @DisplayName("-ai-provider openai sets aiOptions.provider=OPENAI")
    @Tag("positive")
    void parse_aiProviderOpenai_setsProvider() {
        assertEquals(AiProvider.OPENAI, CliArgs.parse("-ai-provider", "openai").aiOptions().provider());
    }

    @Test
    @DisplayName("-ai-provider ANTHROPIC (uppercase) sets aiOptions.provider=ANTHROPIC")
    @Tag("positive")
    void parse_aiProviderAnthropicUppercase_setsProvider() {
        assertEquals(AiProvider.ANTHROPIC, CliArgs.parse("-ai-provider", "ANTHROPIC").aiOptions().provider());
    }

    @Test
    @DisplayName("-ai-model gpt-4o-mini sets aiOptions.modelName=gpt-4o-mini")
    @Tag("positive")
    void parse_aiModel_setsModelName() {
        assertEquals("gpt-4o-mini", CliArgs.parse("-ai-model", "gpt-4o-mini").aiOptions().modelName());
    }

    @Test
    @DisplayName("-ai-base-url http://custom sets aiOptions.baseUrl=http://custom")
    @Tag("positive")
    void parse_aiBaseUrl_setsBaseUrl() {
        assertEquals("http://custom", CliArgs.parse("-ai-base-url", "http://custom").aiOptions().baseUrl());
    }

    @Test
    @DisplayName("-ai-api-key sk-xxx sets aiOptions.apiKey=sk-xxx")
    @Tag("positive")
    void parse_aiApiKey_setsApiKey() {
        assertEquals("sk-xxx", CliArgs.parse("-ai-api-key", "sk-xxx").aiOptions().apiKey());
    }

    @Test
    @DisplayName("-ai-api-key-env MY_KEY sets aiOptions.apiKeyEnv=MY_KEY")
    @Tag("positive")
    void parse_aiApiKeyEnv_setsApiKeyEnv() {
        assertEquals("MY_KEY", CliArgs.parse("-ai-api-key-env", "MY_KEY").aiOptions().apiKeyEnv());
    }

    @Test
    @DisplayName("-ai-taxonomy-mode optimized sets aiOptions.taxonomyMode=OPTIMIZED")
    @Tag("positive")
    void parse_aiTaxonomyMode_setsOptimized() {
        assertEquals(AiOptions.TaxonomyMode.OPTIMIZED,
                CliArgs.parse("-ai-taxonomy-mode", "optimized").aiOptions().taxonomyMode());
    }

    @Test
    @DisplayName("-ai-max-class-chars 50000 sets aiOptions.maxClassChars=50000")
    @Tag("positive")
    void parse_aiMaxClassChars_setsValue() {
        assertEquals(50000, CliArgs.parse("-ai-max-class-chars", "50000").aiOptions().maxClassChars());
    }

    @Test
    @DisplayName("-ai-timeout-sec 30 sets aiOptions.timeout=Duration.ofSeconds(30)")
    @Tag("positive")
    void parse_aiTimeoutSec_setsTimeout() {
        assertEquals(Duration.ofSeconds(30), CliArgs.parse("-ai-timeout-sec", "30").aiOptions().timeout());
    }

    @Test
    @DisplayName("-ai-max-retries 3 sets aiOptions.maxRetries=3")
    @Tag("positive")
    void parse_aiMaxRetries_setsMaxRetries() {
        assertEquals(3, CliArgs.parse("-ai-max-retries", "3").aiOptions().maxRetries());
    }

    // -------------------------------------------------------------------------
    // Manual mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-manual-prepare workDir responseDir creates ManualMode.Prepare with correct paths")
    @Tag("positive")
    void parse_manualPrepare_createsPrepareModeWithPaths() {
        CliConfig cfg = CliArgs.parse("-manual-prepare", "/tmp/work", "/tmp/responses");
        assertNotNull(cfg.manualMode());
        assertInstanceOf(ManualMode.Prepare.class, cfg.manualMode());
        ManualMode.Prepare prepare = (ManualMode.Prepare) cfg.manualMode();
        assertEquals(Path.of("/tmp/work"), prepare.workDir());
        assertEquals(Path.of("/tmp/responses"), prepare.responseDir());
    }

    @Test
    @DisplayName("-manual-consume workDir responseDir creates ManualMode.Consume with correct paths")
    @Tag("positive")
    void parse_manualConsume_createsConsumeModeWithPaths() {
        CliConfig cfg = CliArgs.parse("-manual-consume", "/tmp/work", "/tmp/responses");
        assertNotNull(cfg.manualMode());
        assertInstanceOf(ManualMode.Consume.class, cfg.manualMode());
        ManualMode.Consume consume = (ManualMode.Consume) cfg.manualMode();
        assertEquals(Path.of("/tmp/work"), consume.workDir());
        assertEquals(Path.of("/tmp/responses"), consume.responseDir());
    }

    // -------------------------------------------------------------------------
    // Positional arguments (paths)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("positional non-flag argument is added to paths list")
    @Tag("positive")
    void parse_positionalArg_addedToPaths() {
        CliConfig cfg = CliArgs.parse("/some/path");
        assertEquals(1, cfg.paths().size());
        assertEquals(Path.of("/some/path"), cfg.paths().get(0));
    }

    @Test
    @DisplayName("multiple positional arguments are all added to paths list")
    @Tag("positive")
    void parse_multiplePositionalArgs_allAddedToPaths() {
        CliConfig cfg = CliArgs.parse("/path/a", "/path/b", "/path/c");
        assertEquals(3, cfg.paths().size());
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("unknown -xyz flag throws IllegalArgumentException")
    @Tag("negative")
    void parse_unknownFlag_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse("-xyz"));
    }

    @Test
    @DisplayName("unknown -ai-unknown flag throws IllegalArgumentException")
    @Tag("negative")
    void parse_unknownAiFlag_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> CliArgs.parse("-ai-unknown"));
    }

    @Test
    @DisplayName("-file-suffix with no following argument throws IllegalArgumentException mentioning the flag")
    @Tag("negative")
    void parse_fileSuffixMissingValue_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse("-file-suffix"));
        assertTrue(ex.getMessage().contains("-file-suffix"), ex.getMessage());
    }

    @Test
    @DisplayName("-ai-model with no following argument throws IllegalArgumentException mentioning the flag")
    @Tag("negative")
    void parse_aiModelMissingValue_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse("-ai-model"));
        assertTrue(ex.getMessage().contains("-ai-model"), ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // -config flag
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-config with valid YAML file applies YAML values (outputMode sarif, contentHash true)")
    @Tag("positive")
    void parse_configWithValidYaml_appliesYamlValues(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile,
                "outputMode: sarif\ncontentHash: true\n", StandardCharsets.UTF_8);

        CliConfig cfg = CliArgs.parse("-config", configFile.toString());

        assertEquals(OutputMode.SARIF, cfg.outputMode());
        assertTrue(cfg.contentHash());
    }

    @Test
    @DisplayName("-config with non-existent file throws IllegalArgumentException")
    @Tag("negative")
    void parse_configNonExistentFile_throwsIllegalArgumentException(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("nonexistent.yaml");
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse("-config", missing.toString()));
    }

    @Test
    @DisplayName("-config with full ai section applies all twelve AI option fields")
    @Tag("positive")
    void parse_configWithFullAiSection_appliesAllTwelveAiFields(@TempDir Path tempDir) throws IOException {
        Path taxonomyPath = tempDir.resolve("taxonomy.txt");
        // Use forward slashes so the YAML value is portable across OS (Java accepts / everywhere)
        String taxonomyYamlPath = taxonomyPath.toString().replace('\\', '/');

        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                ai:
                  enabled: true
                  provider: openai
                  model: gpt-4o-mini
                  baseUrl: http://custom-host
                  apiKey: sk-test-key
                  apiKeyEnv: MY_KEY_ENV
                  taxonomyFile: %s
                  taxonomyMode: optimized
                  maxClassChars: 50000
                  timeoutSec: 30
                  maxRetries: 5
                  confidence: true
                """.formatted(taxonomyYamlPath), StandardCharsets.UTF_8);

        CliConfig cfg = CliArgs.parse("-config", configFile.toString());
        AiOptions ai = cfg.aiOptions();

        assertTrue(ai.enabled());
        assertEquals(AiProvider.OPENAI, ai.provider());
        assertEquals("gpt-4o-mini", ai.modelName());
        assertEquals("http://custom-host", ai.baseUrl());
        assertEquals("sk-test-key", ai.apiKey());
        assertEquals("MY_KEY_ENV", ai.apiKeyEnv());
        assertEquals(taxonomyPath.toAbsolutePath().normalize(),
                ai.taxonomyFile().toAbsolutePath().normalize());
        assertEquals(AiOptions.TaxonomyMode.OPTIMIZED, ai.taxonomyMode());
        assertEquals(50000, ai.maxClassChars());
        assertEquals(Duration.ofSeconds(30), ai.timeout());
        assertEquals(5, ai.maxRetries());
        assertTrue(ai.confidence());
    }

    // -------------------------------------------------------------------------
    // -security-only flag
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-security-only flag sets securityOnly=true")
    @Tag("positive")
    void parse_securityOnlyFlag_setsTrue() {
        assertTrue(CliArgs.parse("-security-only").securityOnly());
    }

    @Test
    @DisplayName("securityOnly from YAML config is applied as default")
    @Tag("positive")
    void parse_configWithSecurityOnlyTrue_setsFlag(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "securityOnly: true\n", StandardCharsets.UTF_8);

        assertTrue(CliArgs.parse("-config", configFile.toString()).securityOnly());
    }

    // -------------------------------------------------------------------------
    // -override-file flag
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("-override-file /tmp/overrides.yaml sets overrideFile path")
    @Tag("positive")
    void parse_overrideFileFlag_setsPath() {
        CliConfig cfg = CliArgs.parse("-override-file", "/tmp/overrides.yaml");
        assertEquals(Path.of("/tmp/overrides.yaml"), cfg.overrideFile());
    }

    @Test
    @DisplayName("overrideFile from YAML config is applied as default")
    @Tag("positive")
    void parse_configWithOverrideFile_setsPath(@TempDir Path tempDir) throws IOException {
        Path overrides = tempDir.resolve("overrides.yaml");
        String overridesYamlPath = overrides.toString().replace('\\', '/');
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile,
                "overrideFile: " + overridesYamlPath + "\n", StandardCharsets.UTF_8);

        CliConfig cfg = CliArgs.parse("-config", configFile.toString());

        assertEquals(overrides.toAbsolutePath().normalize(),
                cfg.overrideFile().toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("CLI -override-file overrides YAML overrideFile value")
    @Tag("positive")
    void parse_cliOverrideFileOverridesYaml(@TempDir Path tempDir) throws IOException {
        Path yamlOverride = tempDir.resolve("yaml-overrides.yaml");
        Path cliOverride = tempDir.resolve("cli-overrides.yaml");
        String yamlPath = yamlOverride.toString().replace('\\', '/');
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile,
                "overrideFile: " + yamlPath + "\n", StandardCharsets.UTF_8);

        CliConfig cfg = CliArgs.parse("-config", configFile.toString(),
                "-override-file", cliOverride.toString());

        assertEquals(cliOverride.toAbsolutePath().normalize(),
                cfg.overrideFile().toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("-config with ai.enabled=false and ai.confidence=false leaves those flags unset")
    @Tag("positive")
    void parse_configWithAiEnabledFalseAndConfidenceFalse_doesNotActivate(@TempDir Path tempDir)
            throws IOException {
        // Boolean.TRUE.equals(false) == false → builder.enabled() and builder.confidence()
        // must NOT be called; both remain at their default value of false.
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                ai:
                  enabled: false
                  provider: anthropic
                  confidence: false
                """, StandardCharsets.UTF_8);

        CliConfig cfg = CliArgs.parse("-config", configFile.toString());
        AiOptions ai = cfg.aiOptions();

        assertFalse(ai.enabled(),   "enabled:false in YAML must not activate AI");
        assertFalse(ai.confidence(), "confidence:false in YAML must not set confidence flag");
        // provider IS a non-null string field and must be applied regardless
        assertEquals(AiProvider.ANTHROPIC, ai.provider());
    }
}
