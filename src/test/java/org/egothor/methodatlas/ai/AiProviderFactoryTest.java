package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

/**
 * Unit tests for {@link AiProviderFactory}.
 *
 * <p>
 * This class verifies that the factory creates the correct {@link AiProviderClient}
 * implementation for each configured {@link AiProvider}, handles availability
 * checks, and produces the expected {@link AiSuggestionException} messages when
 * a provider is unavailable or no provider can be selected in AUTO mode.
 * </p>
 */
@Tag("unit")
@Tag("ai-provider-factory")
class AiProviderFactoryTest {

    @Test
    @DisplayName("create with OLLAMA provider returns OllamaClient without performing an availability check")
    @Tag("positive")
    void create_withOllamaProvider_returnsOllamaClientWithoutAvailabilityCheck() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OLLAMA).build();

        try (MockedConstruction<OllamaClient> mocked = mockConstruction(OllamaClient.class)) {
            AiProviderClient client = AiProviderFactory.create(options);

            assertInstanceOf(OllamaClient.class, client);
            assertEquals(1, mocked.constructed().size());
            assertSame(mocked.constructed().get(0), client);
        }
    }

    @Test
    @DisplayName("create with OPENAI provider returns OpenAiCompatibleClient when isAvailable() is true")
    @Tag("positive")
    void create_withOpenAiProvider_returnsOpenAiCompatibleClientWhenAvailable() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENAI).apiKey("sk-test-value")
                .build();

        try (MockedConstruction<OpenAiCompatibleClient> mocked = mockConstruction(OpenAiCompatibleClient.class,
                (mock, ctx) -> when(mock.isAvailable()).thenReturn(true))) {

            AiProviderClient client = AiProviderFactory.create(options);

            assertInstanceOf(OpenAiCompatibleClient.class, client);
            assertEquals(1, mocked.constructed().size());
            assertSame(mocked.constructed().get(0), client);
        }
    }

    @Test
    @DisplayName("create with OPENAI provider throws AiSuggestionException with 'OpenAI API key missing' when unavailable")
    @Tag("negative")
    void create_withOpenAiProvider_throwsWhenUnavailable() {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENAI).build();

        try (MockedConstruction<OpenAiCompatibleClient> mocked = mockConstruction(OpenAiCompatibleClient.class,
                (mock, ctx) -> when(mock.isAvailable()).thenReturn(false))) {

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> AiProviderFactory.create(options));

            assertEquals("OpenAI API key missing", ex.getMessage());
            assertEquals(1, mocked.constructed().size());
        }
    }

    @Test
    @DisplayName("create with OPENROUTER provider returns OpenAiCompatibleClient when isAvailable() is true")
    @Tag("positive")
    void create_withOpenRouterProvider_returnsOpenAiCompatibleClientWhenAvailable() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENROUTER).apiKey("or-test-key")
                .build();

        try (MockedConstruction<OpenAiCompatibleClient> mocked = mockConstruction(OpenAiCompatibleClient.class,
                (mock, ctx) -> when(mock.isAvailable()).thenReturn(true))) {

            AiProviderClient client = AiProviderFactory.create(options);

            assertInstanceOf(OpenAiCompatibleClient.class, client);
            assertEquals(1, mocked.constructed().size());
            assertSame(mocked.constructed().get(0), client);
        }
    }

    @Test
    @DisplayName("create with OPENROUTER provider throws AiSuggestionException with 'OpenRouter API key missing' when unavailable")
    @Tag("negative")
    void create_withOpenRouterProvider_throwsWhenUnavailable() {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENROUTER).build();

        try (MockedConstruction<OpenAiCompatibleClient> mocked = mockConstruction(OpenAiCompatibleClient.class,
                (mock, ctx) -> when(mock.isAvailable()).thenReturn(false))) {

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> AiProviderFactory.create(options));

            assertEquals("OpenRouter API key missing", ex.getMessage());
            assertEquals(1, mocked.constructed().size());
        }
    }

    @Test
    @DisplayName("create with ANTHROPIC provider returns AnthropicClient when isAvailable() is true")
    @Tag("positive")
    void create_withAnthropicProvider_returnsAnthropicClientWhenAvailable() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.ANTHROPIC)
                .apiKey("anthropic-test-key").build();

        try (MockedConstruction<AnthropicClient> mocked = mockConstruction(AnthropicClient.class,
                (mock, ctx) -> when(mock.isAvailable()).thenReturn(true))) {

            AiProviderClient client = AiProviderFactory.create(options);

            assertInstanceOf(AnthropicClient.class, client);
            assertEquals(1, mocked.constructed().size());
            assertSame(mocked.constructed().get(0), client);
        }
    }

    @Test
    @DisplayName("create with ANTHROPIC provider throws AiSuggestionException with 'Anthropic API key missing' when unavailable")
    @Tag("negative")
    void create_withAnthropicProvider_throwsWhenUnavailable() {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.ANTHROPIC).build();

        try (MockedConstruction<AnthropicClient> mocked = mockConstruction(AnthropicClient.class,
                (mock, ctx) -> when(mock.isAvailable()).thenReturn(false))) {

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> AiProviderFactory.create(options));

            assertEquals("Anthropic API key missing", ex.getMessage());
            assertEquals(1, mocked.constructed().size());
        }
    }

    @Test
    @DisplayName("create with AUTO provider returns OllamaClient when Ollama isAvailable() is true")
    @Tag("positive")
    void create_withAutoProvider_returnsOllamaWhenAvailable() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.AUTO).modelName("qwen2.5-coder:7b")
                .baseUrl("http://localhost:11434").build();

        try (MockedConstruction<OllamaClient> ollamaMocked = mockConstruction(OllamaClient.class,
                (mock, ctx) -> when(mock.isAvailable()).thenReturn(true));
                MockedConstruction<OpenAiCompatibleClient> openAiMocked = mockConstruction(
                        OpenAiCompatibleClient.class)) {

            AiProviderClient client = AiProviderFactory.create(options);

            assertInstanceOf(OllamaClient.class, client);
            assertEquals(1, ollamaMocked.constructed().size());
            assertSame(ollamaMocked.constructed().get(0), client);
            assertEquals(0, openAiMocked.constructed().size());
        }
    }

    @Test
    @DisplayName("create with AUTO provider falls back to OpenAiCompatibleClient when Ollama unavailable but API key is present")
    @Tag("positive")
    void create_withAutoProvider_fallsBackToOpenAiCompatibleWhenOllamaUnavailableAndApiKeyPresent() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.AUTO).modelName("gpt-4o-mini")
                .baseUrl("https://api.openai.com").apiKey("sk-test-value").build();

        try (MockedConstruction<OllamaClient> ollamaMocked = mockConstruction(OllamaClient.class,
                (mock, ctx) -> when(mock.isAvailable()).thenReturn(false));
                MockedConstruction<OpenAiCompatibleClient> openAiMocked = mockConstruction(
                        OpenAiCompatibleClient.class)) {

            AiProviderClient client = AiProviderFactory.create(options);

            assertInstanceOf(OpenAiCompatibleClient.class, client);
            assertEquals(1, ollamaMocked.constructed().size());
            assertEquals(1, openAiMocked.constructed().size());
            assertSame(openAiMocked.constructed().get(0), client);
        }
    }

    @Test
    @DisplayName("create with AUTO provider throws AiSuggestionException when Ollama unavailable and no API key is configured")
    @Tag("negative")
    void create_withAutoProvider_throwsWhenOllamaUnavailableAndNoApiKeyConfigured() {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.AUTO).build();

        try (MockedConstruction<OllamaClient> ollamaMocked = mockConstruction(OllamaClient.class,
                (mock, ctx) -> when(mock.isAvailable()).thenReturn(false));
                MockedConstruction<OpenAiCompatibleClient> openAiMocked = mockConstruction(
                        OpenAiCompatibleClient.class)) {

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> AiProviderFactory.create(options));

            assertEquals("No AI provider available. Ollama is not reachable and no API key is configured.",
                    ex.getMessage());
            assertEquals(1, ollamaMocked.constructed().size());
            assertEquals(0, openAiMocked.constructed().size());
        }
    }
}
