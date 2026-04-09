package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class AiOptionsTest {

    @Test
    void builder_defaults_areStableAndValid() {
        AiOptions options = AiOptions.builder().build();

        assertEquals(false, options.enabled());
        assertEquals(AiProvider.AUTO, options.provider());
        assertEquals(AiOptions.DEFAULT_MODEL, options.modelName());
        assertEquals("http://localhost:11434", options.baseUrl());
        assertNull(options.apiKey());
        assertNull(options.apiKeyEnv());
        assertNull(options.taxonomyFile());
        assertEquals(AiOptions.TaxonomyMode.DEFAULT, options.taxonomyMode());
        assertEquals(40_000, options.maxClassChars());
        assertEquals(Duration.ofSeconds(90), options.timeout());
        assertEquals(1, options.maxRetries());
    }

    @Test
    void defaultModel_constantMatchesBuilderDefault() {
        AiOptions options = AiOptions.builder().build();
        assertEquals(AiOptions.DEFAULT_MODEL, options.modelName(),
                "Builder default model must equal the DEFAULT_MODEL constant");
    }

    @Test
    void builder_usesOllamaDefaultBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.OLLAMA).build();

        assertEquals("http://localhost:11434", options.baseUrl());
    }

    @Test
    void builder_usesAutoDefaultBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.AUTO).build();

        assertEquals("http://localhost:11434", options.baseUrl());
    }

    @Test
    void builder_usesOpenAiDefaultBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.OPENAI).build();

        assertEquals("https://api.openai.com", options.baseUrl());
    }

    @Test
    void builder_usesOpenRouterDefaultBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.OPENROUTER).build();

        assertEquals("https://openrouter.ai/api", options.baseUrl());
    }

    @Test
    void builder_usesAnthropicDefaultBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.ANTHROPIC).build();

        assertEquals("https://api.anthropic.com", options.baseUrl());
    }

    @Test
    void builder_preservesExplicitBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.OPENAI)
                .baseUrl("https://internal-gateway.example.test/openai").build();

        assertEquals("https://internal-gateway.example.test/openai", options.baseUrl());
    }

    @Test
    void builder_treatsNullProviderAsAuto() {
        AiOptions options = AiOptions.builder().provider(null).build();

        assertEquals(AiProvider.AUTO, options.provider());
        assertEquals("http://localhost:11434", options.baseUrl());
    }

    @Test
    void resolvedApiKey_prefersDirectApiKey() {
        AiOptions options = AiOptions.builder().apiKey("sk-direct-value").apiKeyEnv("SHOULD_NOT_BE_USED").build();

        assertEquals("sk-direct-value", options.resolvedApiKey());
    }

    @Test
    void resolvedApiKey_returnsNullWhenDirectKeyIsBlankAndEnvIsMissing() {
        AiOptions options = AiOptions.builder().apiKey("   ").apiKeyEnv("METHODATLAS_TEST_ENV_NOT_PRESENT").build();

        assertNull(options.resolvedApiKey());
    }

    @Test
    void resolvedApiKey_returnsNullWhenNeitherDirectNorEnvAreConfigured() {
        AiOptions options = AiOptions.builder().build();

        assertNull(options.resolvedApiKey());
    }

    @Test
    void canonicalConstructor_rejectsNullProvider() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new AiOptions(true, null, "gpt-4o-mini", "https://api.openai.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, Duration.ofSeconds(30), 1));

        assertEquals("provider", ex.getMessage());
    }

    @Test
    void canonicalConstructor_rejectsNullModelName() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new AiOptions(true, AiProvider.OPENAI, null, "https://api.openai.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, Duration.ofSeconds(30), 1));

        assertEquals("modelName", ex.getMessage());
    }

    @Test
    void canonicalConstructor_rejectsNullTimeout() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new AiOptions(true, AiProvider.OPENAI, "gpt-4o-mini", "https://api.openai.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, null, 1));

        assertEquals("timeout", ex.getMessage());
    }

    @Test
    void canonicalConstructor_rejectsNullTaxonomyMode() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> new AiOptions(true, AiProvider.OPENAI,
                "gpt-4o-mini", "https://api.openai.com", null, null, null, null, 40_000, Duration.ofSeconds(30), 1));

        assertEquals("taxonomyMode", ex.getMessage());
    }

    @Test
    void canonicalConstructor_rejectsBlankBaseUrl() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AiOptions(true, AiProvider.OPENAI, "gpt-4o-mini", "   ", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, Duration.ofSeconds(30), 1));

        assertEquals("baseUrl must not be blank", ex.getMessage());
    }

    @Test
    void canonicalConstructor_rejectsNonPositiveMaxClassChars() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AiOptions(true, AiProvider.OPENAI, "gpt-4o-mini", "https://api.openai.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 0, Duration.ofSeconds(30), 1));

        assertEquals("maxClassChars must be > 0", ex.getMessage());
    }

    @Test
    void canonicalConstructor_rejectsNegativeMaxRetries() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AiOptions(true, AiProvider.OPENAI, "gpt-4o-mini", "https://api.openai.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, Duration.ofSeconds(30), -1));

        assertEquals("maxRetries must be >= 0", ex.getMessage());
    }

    @Test
    void builder_allowsFullCustomization() {
        Path taxonomyFile = Path.of("src/test/resources/security-taxonomy.yaml");
        Duration timeout = Duration.ofSeconds(15);

        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.ANTHROPIC)
                .modelName("claude-3-5-sonnet").baseUrl("https://proxy.example.test/anthropic").apiKey("test-api-key")
                .apiKeyEnv("IGNORED_ENV").taxonomyFile(taxonomyFile).taxonomyMode(AiOptions.TaxonomyMode.OPTIMIZED)
                .maxClassChars(12_345).timeout(timeout).maxRetries(4).build();

        assertEquals(true, options.enabled());
        assertEquals(AiProvider.ANTHROPIC, options.provider());
        assertEquals("claude-3-5-sonnet", options.modelName());
        assertEquals("https://proxy.example.test/anthropic", options.baseUrl());
        assertEquals("test-api-key", options.apiKey());
        assertEquals("IGNORED_ENV", options.apiKeyEnv());
        assertEquals(taxonomyFile, options.taxonomyFile());
        assertEquals(AiOptions.TaxonomyMode.OPTIMIZED, options.taxonomyMode());
        assertEquals(12_345, options.maxClassChars());
        assertEquals(timeout, options.timeout());
        assertEquals(4, options.maxRetries());
    }
}
