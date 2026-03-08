package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class AiProviderFactoryTest {

    @Test
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
    void create_withOpenAiProvider_returnsOpenAiCompatibleClientWhenAvailable() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENAI).apiKey("sk-test-value")
                .build();

        try (MockedConstruction<OpenAiCompatibleClient> mocked = mockConstruction(OpenAiCompatibleClient.class,
                (mock, context) -> when(mock.isAvailable()).thenReturn(true))) {

            AiProviderClient client = AiProviderFactory.create(options);

            assertInstanceOf(OpenAiCompatibleClient.class, client);
            assertEquals(1, mocked.constructed().size());
            assertSame(mocked.constructed().get(0), client);
        }
    }

    @Test
    void create_withOpenAiProvider_throwsWhenUnavailable() {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENAI).build();

        try (MockedConstruction<OpenAiCompatibleClient> mocked = mockConstruction(OpenAiCompatibleClient.class,
                (mock, context) -> when(mock.isAvailable()).thenReturn(false))) {

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> AiProviderFactory.create(options));

            assertEquals("OpenAI API key missing", ex.getMessage());
            assertEquals(1, mocked.constructed().size());
        }
    }

    @Test
    void create_withOpenRouterProvider_returnsOpenAiCompatibleClientWhenAvailable() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENROUTER).apiKey("or-test-key")
                .build();

        try (MockedConstruction<OpenAiCompatibleClient> mocked = mockConstruction(OpenAiCompatibleClient.class,
                (mock, context) -> when(mock.isAvailable()).thenReturn(true))) {

            AiProviderClient client = AiProviderFactory.create(options);

            assertInstanceOf(OpenAiCompatibleClient.class, client);
            assertEquals(1, mocked.constructed().size());
            assertSame(mocked.constructed().get(0), client);
        }
    }

    @Test
    void create_withOpenRouterProvider_throwsWhenUnavailable() {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.OPENROUTER).build();

        try (MockedConstruction<OpenAiCompatibleClient> mocked = mockConstruction(OpenAiCompatibleClient.class,
                (mock, context) -> when(mock.isAvailable()).thenReturn(false))) {

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> AiProviderFactory.create(options));

            assertEquals("OpenRouter API key missing", ex.getMessage());
            assertEquals(1, mocked.constructed().size());
        }
    }

    @Test
    void create_withAnthropicProvider_returnsAnthropicClientWhenAvailable() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.ANTHROPIC)
                .apiKey("anthropic-test-key").build();

        try (MockedConstruction<AnthropicClient> mocked = mockConstruction(AnthropicClient.class,
                (mock, context) -> when(mock.isAvailable()).thenReturn(true))) {

            AiProviderClient client = AiProviderFactory.create(options);

            assertInstanceOf(AnthropicClient.class, client);
            assertEquals(1, mocked.constructed().size());
            assertSame(mocked.constructed().get(0), client);
        }
    }

    @Test
    void create_withAnthropicProvider_throwsWhenUnavailable() {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.ANTHROPIC).build();

        try (MockedConstruction<AnthropicClient> mocked = mockConstruction(AnthropicClient.class,
                (mock, context) -> when(mock.isAvailable()).thenReturn(false))) {

            AiSuggestionException ex = assertThrows(AiSuggestionException.class,
                    () -> AiProviderFactory.create(options));

            assertEquals("Anthropic API key missing", ex.getMessage());
            assertEquals(1, mocked.constructed().size());
        }
    }

    @Test
    void create_withAutoProvider_returnsOllamaWhenAvailable() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.AUTO).modelName("qwen2.5-coder:7b")
                .baseUrl("http://localhost:11434").build();

        try (MockedConstruction<OllamaClient> ollamaMocked = mockConstruction(OllamaClient.class,
                (mock, context) -> when(mock.isAvailable()).thenReturn(true));
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
    void create_withAutoProvider_fallsBackToOpenAiCompatibleWhenOllamaUnavailableAndApiKeyPresent() throws Exception {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.AUTO).modelName("gpt-4o-mini")
                .baseUrl("https://api.openai.com").apiKey("sk-test-value").build();

        try (MockedConstruction<OllamaClient> ollamaMocked = mockConstruction(OllamaClient.class,
                (mock, context) -> when(mock.isAvailable()).thenReturn(false));
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
    void create_withAutoProvider_throwsWhenOllamaUnavailableAndNoApiKeyConfigured() {
        AiOptions options = AiOptions.builder().enabled(true).provider(AiProvider.AUTO).build();

        try (MockedConstruction<OllamaClient> ollamaMocked = mockConstruction(OllamaClient.class,
                (mock, context) -> when(mock.isAvailable()).thenReturn(false));
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
