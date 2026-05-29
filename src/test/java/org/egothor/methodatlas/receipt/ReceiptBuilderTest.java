// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiProvider;
import org.egothor.methodatlas.emit.OutputMode;

/**
 * Unit tests for {@link ReceiptBuilder}.
 *
 * <p>
 * The tests cover the {@code configHash} determinism contract, the
 * single-input-changes-hash invariant, and the {@code deterministicReplay}
 * truth table for the four interesting (AI×cache) corners.
 * </p>
 */
class ReceiptBuilderTest {

    @Test
    void deterministicReplay_trueWhenAiDisabled() throws IOException {
        CliConfig config = config(/*aiEnabled*/ false, /*aiCacheFile*/ null);
        ReproducibilityReceipt receipt = ReceiptBuilder.build(config, "1.0.0", "CSV");
        assertTrue(receipt.deterministicReplay(),
                "AI disabled must yield deterministicReplay = true");
    }

    @Test
    void deterministicReplay_trueWhenAiCachePresent(@TempDir Path tempDir) throws IOException {
        Path cache = writeFile(tempDir, "cache.csv", "fqcn,method\n");
        CliConfig config = config(/*aiEnabled*/ true, cache);
        ReproducibilityReceipt receipt = ReceiptBuilder.build(config, "1.0.0", "SARIF");
        assertTrue(receipt.deterministicReplay(),
                "AI + cache must yield deterministicReplay = true");
    }

    @Test
    void deterministicReplay_falseWhenAiEnabledWithoutCache() throws IOException {
        CliConfig config = config(/*aiEnabled*/ true, /*aiCacheFile*/ null);
        ReproducibilityReceipt receipt = ReceiptBuilder.build(config, "1.0.0", "SARIF");
        assertFalse(receipt.deterministicReplay(),
                "AI without cache means a live model call could vary the output");
    }

    @Test
    void configHash_isDeterministicAcrossCalls() throws IOException {
        CliConfig config = config(false, null);
        ReproducibilityReceipt one = ReceiptBuilder.build(config, "1.0.0", "CSV");
        ReproducibilityReceipt two = ReceiptBuilder.build(config, "1.0.0", "CSV");
        assertEquals(one.configHash(), two.configHash(),
                "Identical inputs must produce identical configHash values");
    }

    @Test
    void configHash_changesWhenToolVersionChanges() throws IOException {
        CliConfig config = config(false, null);
        String a = ReceiptBuilder.build(config, "1.0.0", "CSV").configHash();
        String b = ReceiptBuilder.build(config, "1.0.1", "CSV").configHash();
        assertNotEquals(a, b, "configHash must depend on the tool version");
    }

    @Test
    void configHash_changesWhenAiProviderChanges() throws IOException {
        CliConfig openAi = configWithAi(AiProvider.OPENAI, "gpt-4o", null);
        CliConfig anthropic = configWithAi(AiProvider.ANTHROPIC, "gpt-4o", null);
        assertNotEquals(
                ReceiptBuilder.build(openAi, "1.0.0", "SARIF").configHash(),
                ReceiptBuilder.build(anthropic, "1.0.0", "SARIF").configHash(),
                "Switching AI provider must change configHash");
    }

    @Test
    void configHash_changesWhenAiModelChanges() throws IOException {
        CliConfig a = configWithAi(AiProvider.OPENAI, "gpt-4o", null);
        CliConfig b = configWithAi(AiProvider.OPENAI, "gpt-4o-mini", null);
        assertNotEquals(
                ReceiptBuilder.build(a, "1.0.0", "SARIF").configHash(),
                ReceiptBuilder.build(b, "1.0.0", "SARIF").configHash(),
                "Switching AI model must change configHash");
    }

    @Test
    void configHash_changesWhenOverrideFileContentChanges(@TempDir Path tempDir) throws IOException {
        Path override = writeFile(tempDir, "override.yaml", "version: 1\n");
        CliConfig before = configWithOverride(override);
        String a = ReceiptBuilder.build(before, "1.0.0", "CSV").configHash();
        Files.writeString(override, "version: 2\n", StandardCharsets.UTF_8);
        String b = ReceiptBuilder.build(before, "1.0.0", "CSV").configHash();
        assertNotEquals(a, b,
                "Changing the override file contents must change configHash");
    }

    @Test
    void configHash_changesWhenAiCacheContentChanges(@TempDir Path tempDir) throws IOException {
        Path cache = writeFile(tempDir, "cache.csv", "fqcn,method\n");
        CliConfig config = config(true, cache);
        String a = ReceiptBuilder.build(config, "1.0.0", "SARIF").configHash();
        Files.writeString(cache, "fqcn,method\nA.B,c\n", StandardCharsets.UTF_8);
        String b = ReceiptBuilder.build(config, "1.0.0", "SARIF").configHash();
        assertNotEquals(a, b,
                "Changing the AI cache contents must change configHash");
    }

    @Test
    void configHash_changesWhenTaxonomyFileContentChanges(@TempDir Path tempDir) throws IOException {
        Path tax = writeFile(tempDir, "taxonomy.txt", "auth,access-control\n");
        CliConfig config = configWithTaxonomyFile(tax);
        String a = ReceiptBuilder.build(config, "1.0.0", "SARIF").configHash();
        Files.writeString(tax, "auth,access-control,crypto\n", StandardCharsets.UTF_8);
        String b = ReceiptBuilder.build(config, "1.0.0", "SARIF").configHash();
        assertNotEquals(a, b,
                "Changing the taxonomy file contents must change configHash");
    }

    @Test
    void inputs_overrideFile_pathIsAbsolute(@TempDir Path tempDir) throws IOException {
        Path override = writeFile(tempDir, "overrides.yaml", "k: v\n");
        ReproducibilityReceipt receipt =
                ReceiptBuilder.build(configWithOverride(override), "1.0.0", "CSV");
        assertNotNull(receipt.inputs().overrideFile(), "override artefact must be populated");
        assertEquals(override.toAbsolutePath().toString(),
                receipt.inputs().overrideFile().path(),
                "FileArtifact path must be the absolute path string");
    }

    @Test
    void inputs_omitsAiFieldsWhenAiDisabled() throws IOException {
        ReproducibilityReceipt receipt = ReceiptBuilder.build(config(false, null), "1.0.0", "CSV");
        assertNull(receipt.inputs().aiProvider(), "aiProvider must be null when AI is off");
        assertNull(receipt.inputs().aiModel(), "aiModel must be null when AI is off");
        assertNull(receipt.inputs().promptTemplateHash(),
                "promptTemplateHash must be null when AI is off");
    }

    @Test
    void inputs_omitsTaxonomyFileWhenUsingBuiltIn() throws IOException {
        ReproducibilityReceipt receipt = ReceiptBuilder.build(config(false, null), "1.0.0", "CSV");
        assertNull(receipt.inputs().taxonomyFile(),
                "taxonomyFile must be absent when using a built-in taxonomy");
        assertEquals("DEFAULT", receipt.inputs().builtInTaxonomy(),
                "builtInTaxonomy must record the active TaxonomyMode name");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static CliConfig config(boolean aiEnabled, Path aiCacheFile) {
        return baseConfig(aiOptions(aiEnabled, AiProvider.OPENAI, "gpt-4o", null), null, aiCacheFile);
    }

    private static CliConfig configWithAi(AiProvider provider, String model, Path aiCacheFile) {
        return baseConfig(aiOptions(true, provider, model, null), null, aiCacheFile);
    }

    private static CliConfig configWithOverride(Path overrideFile) {
        return baseConfig(aiOptions(false, AiProvider.OPENAI, "gpt-4o", null), overrideFile, null);
    }

    private static CliConfig configWithTaxonomyFile(Path taxonomyFile) {
        return baseConfig(aiOptions(true, AiProvider.OPENAI, "gpt-4o", taxonomyFile), null, null);
    }

    private static AiOptions aiOptions(boolean enabled, AiProvider provider, String model,
            Path taxonomyFile) {
        AiOptions.Builder b = AiOptions.builder()
                .enabled(enabled)
                .provider(provider)
                .modelName(model);
        if (taxonomyFile != null) {
            b.taxonomyFile(taxonomyFile);
        }
        return b.build();
    }

    private static CliConfig baseConfig(AiOptions aiOptions, Path overrideFile, Path aiCacheFile) {
        return new CliConfig(
                OutputMode.CSV, aiOptions,
                List.of(), List.of("Test.java"), Set.<String>of(), Map.<String, List<String>>of(),
                false, null, false, false, overrideFile,
                false, aiCacheFile, false, null, -1, false, false, 0.0,
                false, null);
    }
}
