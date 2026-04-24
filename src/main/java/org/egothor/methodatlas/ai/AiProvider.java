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
    ANTHROPIC,
    /**
     * Uses an <a href="https://azure.microsoft.com/en-us/products/ai-services/openai-service">Azure
     * OpenAI Service</a> deployment for AI inference.
     *
     * <p>
     * Azure OpenAI is a managed cloud service operated by Microsoft inside a
     * customer-controlled Azure tenant. Unlike the public OpenAI API, requests never
     * leave the organization's Azure environment, making this provider suitable for
     * regulated industries and corporate environments with data-sovereignty
     * requirements.
     * </p>
     *
     * <p>
     * Authentication uses a static resource-scoped API key supplied via the
     * {@code api-key} HTTP header. Entra ID token-based authentication is not
     * currently supported.
     * </p>
     *
     * <p>
     * The request endpoint is constructed from three configuration values:
     * </p>
     *
     * <ul>
     * <li>{@code baseUrl} — the Azure OpenAI resource endpoint, for example
     *     {@code https://contoso.openai.azure.com}</li>
     * <li>{@code modelName} — the <em>deployment name</em> configured in the Azure
     *     portal (not the underlying model family name)</li>
     * <li>{@code apiVersion} — the Azure OpenAI REST API version, for example
     *     {@code 2024-02-01}</li>
     * </ul>
     *
     * <p>
     * The resulting endpoint takes the form:<br>
     * {@code {baseUrl}/openai/deployments/{modelName}/chat/completions?api-version={apiVersion}}
     * </p>
     */
    AZURE_OPENAI,
    /**
     * Uses the <a href="https://console.groq.com/">Groq</a> cloud inference
     * service for AI-based security classification.
     *
     * <p>
     * Groq exposes an OpenAI-compatible REST API served by custom LPU hardware,
     * resulting in very low latency and high throughput. The endpoint is
     * {@code https://api.groq.com/openai}. A free tier is available at
     * <a href="https://console.groq.com/">console.groq.com</a>.
     * </p>
     *
     * <p>
     * Authentication uses a Bearer token supplied via the standard
     * {@code Authorization} header, identical to the OpenAI and OpenRouter
     * providers.
     * </p>
     */
    GROQ,
    /**
     * Uses the <a href="https://x.ai/">xAI</a> API to access Grok models.
     *
     * <p>
     * The xAI platform exposes an OpenAI-compatible REST endpoint at
     * {@code https://api.x.ai/v1}. Authentication uses a Bearer token.
     * API keys are available at <a href="https://console.x.ai/">console.x.ai</a>.
     * </p>
     */
    XAI,
    /**
     * Uses the <a href="https://github.com/marketplace/models">GitHub Models</a>
     * free inference service.
     *
     * <p>
     * GitHub Models exposes an OpenAI-compatible endpoint at
     * {@code https://models.inference.ai.azure.com} and authenticates with a
     * standard GitHub personal access token ({@code GITHUB_TOKEN}). The free
     * tier is available to any GitHub account and covers a broad selection of
     * models from multiple providers (OpenAI, Meta Llama, Mistral, and others).
     * </p>
     *
     * <p>
     * This provider is well-suited for CI pipelines in open-source projects
     * where {@code GITHUB_TOKEN} is already available as an environment
     * variable without additional secret management.
     * </p>
     */
    GITHUB_MODELS,
    /**
     * Uses the <a href="https://mistral.ai/">Mistral AI</a> API for inference.
     *
     * <p>
     * Mistral exposes an OpenAI-compatible REST endpoint at
     * {@code https://api.mistral.ai/v1}. Authentication uses a Bearer token.
     * A free tier is available at
     * <a href="https://console.mistral.ai/">console.mistral.ai</a>.
     * </p>
     */
    MISTRAL
}
