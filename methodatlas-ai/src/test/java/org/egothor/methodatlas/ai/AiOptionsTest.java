package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AiOptions} and its builder.
 *
 * <p>
 * This class verifies default values, provider-specific base URLs, explicit
 * overrides, API key resolution, and canonical constructor validation rules
 * for null and invalid field values.
 * </p>
 */
@Tag("unit")
@Tag("ai-options")
class AiOptionsTest {

    @Test
    @DisplayName("builder defaults are stable and match documented constants")
    @Tag("positive")
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
        assertEquals(false, options.confidence());
        assertEquals(AiOptions.DEFAULT_API_VERSION, options.apiVersion());
    }

    @Test
    @DisplayName("DEFAULT_MODEL constant equals the default model name from builder")
    @Tag("positive")
    void defaultModel_constantMatchesBuilderDefault() {
        AiOptions options = AiOptions.builder().build();
        assertEquals(AiOptions.DEFAULT_MODEL, options.modelName(),
                "Builder default model must equal the DEFAULT_MODEL constant");
    }

    @Test
    @DisplayName("builder uses Ollama default base URL for OLLAMA provider")
    @Tag("positive")
    void builder_usesOllamaDefaultBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.OLLAMA).build();

        assertEquals("http://localhost:11434", options.baseUrl());
    }

    @Test
    @DisplayName("builder uses Ollama default base URL for AUTO provider")
    @Tag("positive")
    void builder_usesAutoDefaultBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.AUTO).build();

        assertEquals("http://localhost:11434", options.baseUrl());
    }

    @Test
    @DisplayName("builder uses OpenAI default base URL for OPENAI provider")
    @Tag("positive")
    void builder_usesOpenAiDefaultBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.OPENAI).build();

        assertEquals("https://api.openai.com", options.baseUrl());
    }

    @Test
    @DisplayName("builder uses OpenRouter default base URL for OPENROUTER provider")
    @Tag("positive")
    void builder_usesOpenRouterDefaultBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.OPENROUTER).build();

        assertEquals("https://openrouter.ai/api", options.baseUrl());
    }

    @Test
    @DisplayName("builder uses Anthropic default base URL for ANTHROPIC provider")
    @Tag("positive")
    void builder_usesAnthropicDefaultBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.ANTHROPIC).build();

        assertEquals("https://api.anthropic.com", options.baseUrl());
    }

    @Test
    @DisplayName("builder preserves explicit base URL override regardless of provider")
    @Tag("positive")
    void builder_preservesExplicitBaseUrl() {
        AiOptions options = AiOptions.builder().provider(AiProvider.OPENAI)
                .baseUrl("https://internal-gateway.example.test/openai").build();

        assertEquals("https://internal-gateway.example.test/openai", options.baseUrl());
    }

    @Test
    @DisplayName("builder treats null provider as AUTO with Ollama base URL")
    @Tag("edge-case")
    void builder_treatsNullProviderAsAuto() {
        AiOptions options = AiOptions.builder().provider(null).build();

        assertEquals(AiProvider.AUTO, options.provider());
        assertEquals("http://localhost:11434", options.baseUrl());
    }

    @Test
    @DisplayName("resolvedApiKey returns direct key when both apiKey and apiKeyEnv are set")
    @Tag("positive")
    @Tag("security")
    void resolvedApiKey_prefersDirectApiKey() {
        AiOptions options = AiOptions.builder().apiKey("sk-direct-value").apiKeyEnv("SHOULD_NOT_BE_USED").build();

        assertEquals("sk-direct-value", options.resolvedApiKey());
    }

    @Test
    @DisplayName("resolvedApiKey returns null when direct key is blank and env variable is not set")
    @Tag("negative")
    @Tag("security")
    void resolvedApiKey_returnsNullWhenDirectKeyIsBlankAndEnvIsMissing() {
        AiOptions options = AiOptions.builder().apiKey("   ").apiKeyEnv("METHODATLAS_TEST_ENV_NOT_PRESENT").build();

        assertNull(options.resolvedApiKey());
    }

    @Test
    @DisplayName("resolvedApiKey returns null when neither direct key nor env variable are configured")
    @Tag("negative")
    @Tag("security")
    void resolvedApiKey_returnsNullWhenNeitherDirectNorEnvAreConfigured() {
        AiOptions options = AiOptions.builder().build();

        assertNull(options.resolvedApiKey());
    }

    @Test
    @DisplayName("canonical constructor rejects null provider with NullPointerException")
    @Tag("negative")
    void canonicalConstructor_rejectsNullProvider() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new AiOptions(true, null, "gpt-4o-mini", "https://api.openai.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, Duration.ofSeconds(30), 1, false,
                        AiOptions.DEFAULT_API_VERSION));

        assertEquals("provider", ex.getMessage());
    }

    @Test
    @DisplayName("canonical constructor rejects null modelName with NullPointerException")
    @Tag("negative")
    void canonicalConstructor_rejectsNullModelName() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new AiOptions(true, AiProvider.OPENAI, null, "https://api.openai.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, Duration.ofSeconds(30), 1, false,
                        AiOptions.DEFAULT_API_VERSION));

        assertEquals("modelName", ex.getMessage());
    }

    @Test
    @DisplayName("canonical constructor rejects null timeout with NullPointerException")
    @Tag("negative")
    void canonicalConstructor_rejectsNullTimeout() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new AiOptions(true, AiProvider.OPENAI, "gpt-4o-mini", "https://api.openai.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, null, 1, false,
                        AiOptions.DEFAULT_API_VERSION));

        assertEquals("timeout", ex.getMessage());
    }

    @Test
    @DisplayName("canonical constructor rejects null taxonomyMode with NullPointerException")
    @Tag("negative")
    void canonicalConstructor_rejectsNullTaxonomyMode() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> new AiOptions(true, AiProvider.OPENAI,
                "gpt-4o-mini", "https://api.openai.com", null, null, null, null, 40_000, Duration.ofSeconds(30), 1,
                false, AiOptions.DEFAULT_API_VERSION));

        assertEquals("taxonomyMode", ex.getMessage());
    }

    @Test
    @DisplayName("canonical constructor rejects blank baseUrl with IllegalArgumentException")
    @Tag("negative")
    @Tag("security")
    void canonicalConstructor_rejectsBlankBaseUrl() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AiOptions(true, AiProvider.OPENAI, "gpt-4o-mini", "   ", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, Duration.ofSeconds(30), 1, false,
                        AiOptions.DEFAULT_API_VERSION));

        assertEquals("baseUrl must not be blank", ex.getMessage());
    }

    @Test
    @DisplayName("canonical constructor rejects non-positive maxClassChars with IllegalArgumentException")
    @Tag("negative")
    void canonicalConstructor_rejectsNonPositiveMaxClassChars() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AiOptions(true, AiProvider.OPENAI, "gpt-4o-mini", "https://api.openai.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 0, Duration.ofSeconds(30), 1, false,
                        AiOptions.DEFAULT_API_VERSION));

        assertEquals("maxClassChars must be > 0", ex.getMessage());
    }

    @Test
    @DisplayName("canonical constructor rejects negative maxRetries with IllegalArgumentException")
    @Tag("negative")
    void canonicalConstructor_rejectsNegativeMaxRetries() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AiOptions(true, AiProvider.OPENAI, "gpt-4o-mini", "https://api.openai.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, Duration.ofSeconds(30), -1, false,
                        AiOptions.DEFAULT_API_VERSION));

        assertEquals("maxRetries must be >= 0", ex.getMessage());
    }

    @Test
    @DisplayName("canonical constructor accepts maxClassChars=1 (minimum valid positive value)")
    @Tag("edge-case")
    void canonicalConstructor_acceptsMaxClassCharsOfOne() {
        AiOptions options = new AiOptions(true, AiProvider.OPENAI, "gpt-4o-mini", "https://api.openai.com",
                null, null, null, AiOptions.TaxonomyMode.DEFAULT, 1, Duration.ofSeconds(30), 1, false,
                AiOptions.DEFAULT_API_VERSION);

        assertEquals(1, options.maxClassChars());
    }

    @Test
    @DisplayName("canonical constructor accepts maxRetries=0 (no retries is valid)")
    @Tag("edge-case")
    void canonicalConstructor_acceptsMaxRetriesOfZero() {
        AiOptions options = new AiOptions(true, AiProvider.OPENAI, "gpt-4o-mini", "https://api.openai.com",
                null, null, null, AiOptions.TaxonomyMode.DEFAULT, 40_000, Duration.ofSeconds(30), 0, false,
                AiOptions.DEFAULT_API_VERSION);

        assertEquals(0, options.maxRetries());
    }

    @Test
    @DisplayName("resolvedApiKey returns null when apiKey is whitespace-only")
    @Tag("negative")
    @Tag("security")
    void resolvedApiKey_returnsNullWhenApiKeyIsWhitespaceOnly() {
        AiOptions options = AiOptions.builder().apiKey("   \t  ").build();

        assertNull(options.resolvedApiKey());
    }

    @Test
    @DisplayName("builder allows full customization of all fields")
    @Tag("positive")
    void builder_allowsFullCustomization() {
        Path taxonomyFile = Path.of("src/test/resources/security-taxonomy.yaml");
        Duration timeout = Duration.ofSeconds(15);

        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.ANTHROPIC)
                .modelName("claude-3-5-sonnet").baseUrl("https://proxy.example.test/anthropic").apiKey("test-api-key")
                .apiKeyEnv("IGNORED_ENV").taxonomyFile(taxonomyFile).taxonomyMode(AiOptions.TaxonomyMode.OPTIMIZED)
                .maxClassChars(12_345).timeout(timeout).maxRetries(4).confidence(true).build();

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
        assertEquals(true, options.confidence());
        assertEquals(AiOptions.DEFAULT_API_VERSION, options.apiVersion());
    }

    @Test
    @DisplayName("builder accepts explicit apiVersion override")
    @Tag("positive")
    void builder_acceptsApiVersionOverride() {
        AiOptions options = AiOptions.builder()
                .provider(AiProvider.AZURE_OPENAI)
                .baseUrl("https://contoso.openai.azure.com")
                .apiKey("secret-key")
                .apiVersion("2024-08-01-preview")
                .build();

        assertEquals("2024-08-01-preview", options.apiVersion());
    }

    @Test
    @DisplayName("builder uses default apiVersion when none is set")
    @Tag("positive")
    void builder_usesDefaultApiVersion() {
        AiOptions options = AiOptions.builder()
                .provider(AiProvider.AZURE_OPENAI)
                .baseUrl("https://contoso.openai.azure.com")
                .apiKey("secret-key")
                .build();

        assertEquals(AiOptions.DEFAULT_API_VERSION, options.apiVersion());
    }

    @Test
    @DisplayName("builder throws IllegalArgumentException for AZURE_OPENAI when no baseUrl is provided")
    @Tag("negative")
    void builder_throwsForAzureOpenAiWithoutBaseUrl() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AiOptions.builder().provider(AiProvider.AZURE_OPENAI).build());

        assertEquals(
                "baseUrl is required for AZURE_OPENAI: set it to https://<resource>.openai.azure.com",
                ex.getMessage());
    }

    @Test
    @DisplayName("canonical constructor rejects blank apiVersion with IllegalArgumentException")
    @Tag("negative")
    void canonicalConstructor_rejectsBlankApiVersion() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AiOptions(true, AiProvider.AZURE_OPENAI, "my-deployment",
                        "https://contoso.openai.azure.com", null, null, null,
                        AiOptions.TaxonomyMode.DEFAULT, 40_000, Duration.ofSeconds(30), 1, false, ""));

        assertEquals("apiVersion must not be blank", ex.getMessage());
    }
}
