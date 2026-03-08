package org.egothor.methodatlas.ai;

/**
 * Enumeration of supported AI provider implementations used by the
 * {@link org.egothor.methodatlas.ai.AiSuggestionEngine}.
 *
 * <p>
 * Each constant represents a distinct AI platform capable of performing
 * security classification of test sources. The provider selected through
 * {@link AiOptions} determines which concrete client implementation is used for
 * communicating with the external AI service.
 * </p>
 *
 * <p>
 * Provider integrations typically differ in authentication model, request
 * format, endpoint structure, and supported model identifiers. The AI
 * integration layer normalizes these differences so that the rest of the
 * application can interact with a consistent abstraction.
 * </p>
 *
 * <h2>Provider Selection</h2>
 *
 * <p>
 * The selected provider influences:
 * </p>
 * <ul>
 * <li>the HTTP endpoint used for inference requests</li>
 * <li>authentication behavior</li>
 * <li>the model identifier format</li>
 * <li>response normalization logic</li>
 * </ul>
 *
 * <p>
 * When {@link #AUTO} is selected, the system attempts to determine the most
 * suitable provider automatically based on the configured endpoint or local
 * runtime environment.
 * </p>
 *
 * @see AiOptions
 * @see AiSuggestionEngine
 */
public enum AiProvider {
    /**
     * Automatically selects the most appropriate AI provider based on configuration
     * and runtime availability.
     *
     * <p>
     * This mode allows the application to operate with minimal configuration,
     * preferring locally available providers when possible.
     * </p>
     */
    AUTO,
    /**
     * Uses a locally running <a href="https://ollama.ai/">Ollama</a> instance as
     * the AI inference backend.
     *
     * <p>
     * This provider typically communicates with an HTTP endpoint hosted on the
     * local machine and allows the use of locally installed large language models
     * without external API calls.
     * </p>
     */
    OLLAMA,
    /**
     * Uses the OpenAI API for AI inference.
     *
     * <p>
     * Requests are sent to the OpenAI platform using API key authentication and
     * provider-specific model identifiers such as {@code gpt-4} or {@code gpt-4o}.
     * </p>
     */
    OPENAI,
    /**
     * Uses the <a href="https://openrouter.ai/">OpenRouter</a> aggregation service
     * to access multiple AI models through a unified API.
     *
     * <p>
     * OpenRouter acts as a routing layer that forwards requests to different
     * underlying model providers while maintaining a consistent API surface.
     * </p>
     */
    OPENROUTER,
    /**
     * Uses the <a href="https://www.anthropic.com/">Anthropic</a> API for AI
     * inference, typically through models in the Claude family.
     */
    ANTHROPIC
}
