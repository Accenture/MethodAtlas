package org.egothor.methodatlas.ai;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration describing how AI-based enrichment should be
 * performed during a {@link org.egothor.methodatlas.MethodAtlasApp} execution.
 *
 * <p>
 * This record aggregates all runtime parameters required by the AI integration
 * layer, including provider selection, model identification, authentication
 * configuration, taxonomy selection, request limits, and retry behavior.
 * </p>
 *
 * <p>
 * Instances of this record are typically constructed using the associated
 * {@link Builder} and passed to the AI subsystem when initializing an
 * {@link AiSuggestionEngine}. The configuration is immutable once constructed
 * and therefore safe to share between concurrent components.
 * </p>
 *
 * <h2>Configuration Responsibilities</h2>
 *
 * <ul>
 * <li>AI provider selection and endpoint configuration</li>
 * <li>model name resolution</li>
 * <li>API key discovery</li>
 * <li>taxonomy configuration for security classification</li>
 * <li>input size limits for class source submission</li>
 * <li>network timeout configuration</li>
 * <li>retry policy for transient AI failures</li>
 * </ul>
 *
 * <p>
 * Default values are supplied by the {@link Builder} when parameters are not
 * explicitly provided.
 * </p>
 *
 * @param enabled       whether AI enrichment is enabled
 * @param provider      AI provider used to perform analysis
 * @param modelName     provider-specific model identifier
 * @param baseUrl       base API endpoint used by the selected provider
 * @param apiKey        API key used for authentication, if provided directly
 * @param apiKeyEnv     environment variable name containing the API key
 * @param taxonomyFile  optional path to an external taxonomy definition
 * @param taxonomyMode  built-in taxonomy mode to use when no file is provided
 * @param maxClassChars maximum number of characters allowed for class source
 *                      submitted to the AI provider
 * @param timeout       request timeout applied to AI calls
 * @param maxRetries    number of retry attempts for failed AI operations
 *
 * @see AiSuggestionEngine
 * @see Builder
 */
public record AiOptions(boolean enabled, AiProvider provider, String modelName, String baseUrl, String apiKey,
        String apiKeyEnv, Path taxonomyFile, TaxonomyMode taxonomyMode, int maxClassChars, Duration timeout,
        int maxRetries) {
    /**
     * Built-in taxonomy modes used for security classification.
     *
     * <p>
     * These modes determine which internal taxonomy definition is supplied to the
     * AI provider when an external taxonomy file is not configured.
     * </p>
     *
     * <ul>
     * <li>{@link #DEFAULT} – general-purpose taxonomy suitable for human
     * readability</li>
     * <li>{@link #OPTIMIZED} – compact taxonomy optimized for AI classification
     * accuracy</li>
     * </ul>
     */
    public enum TaxonomyMode {
        /**
         * Standard taxonomy definition emphasizing clarity and documentation.
         */
        DEFAULT,
        /**
         * Reduced taxonomy optimized for improved AI classification reliability.
         */
        OPTIMIZED
    }

    /**
     * Default model identifier used when no model is explicitly configured.
     *
     * <p>
     * This constant is intentionally public so that governance processes can
     * locate and track the approved fallback model in version control without
     * searching through builder internals.
     * </p>
     */
    public static final String DEFAULT_MODEL = "qwen2.5-coder:7b";

    /**
     * Canonical constructor performing validation of configuration parameters.
     *
     * <p>
     * The constructor enforces basic invariants required for correct operation of
     * the AI integration layer. Invalid values result in an
     * {@link IllegalArgumentException}.
     * </p>
     *
     * @throws NullPointerException     if required parameters such as
     *                                  {@code provider}, {@code modelName},
     *                                  {@code timeout}, or {@code taxonomyMode} are
     *                                  {@code null}
     * @throws IllegalArgumentException if configuration values violate required
     *                                  constraints
     */
    public AiOptions {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(taxonomyMode, "taxonomyMode");

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (maxClassChars <= 0) {
            throw new IllegalArgumentException("maxClassChars must be > 0");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
    }

    /**
     * Creates a new {@link Builder} used to construct {@link AiOptions} instances.
     *
     * <p>
     * The builder supplies sensible defaults for most configuration values and
     * allows incremental customization before producing the final immutable
     * configuration record.
     * </p>
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Resolves the effective API key used for authenticating AI provider requests.
     *
     * <p>
     * The resolution strategy is:
     * </p>
     *
     * <ol>
     * <li>If {@link #apiKey()} is defined and non-empty, it is returned.</li>
     * <li>If {@link #apiKeyEnv()} is defined, the corresponding environment
     * variable is resolved using {@link System#getenv(String)}.</li>
     * <li>If neither source yields a value, {@code null} is returned.</li>
     * </ol>
     *
     * @return resolved API key or {@code null} if none is available
     */
    public String resolvedApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
            String value = System.getenv(apiKeyEnv);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Mutable builder used to construct validated {@link AiOptions} instances.
     *
     * <p>
     * The builder follows the conventional staged construction pattern, allowing
     * optional parameters to be supplied before producing the final immutable
     * configuration record via {@link #build()}.
     * </p>
     *
     * <p>
     * Reasonable defaults are provided for most parameters so that only
     * provider-specific details typically need to be configured explicitly.
     * </p>
     */
    public static final class Builder {
        private boolean enabled;
        private AiProvider provider = AiProvider.AUTO;
        private String modelName = DEFAULT_MODEL;
        private String baseUrl;
        private String apiKey;
        private String apiKeyEnv;
        private Path taxonomyFile;
        private TaxonomyMode taxonomyMode = TaxonomyMode.DEFAULT;
        private int maxClassChars = 40_000;
        private Duration timeout = Duration.ofSeconds(90);
        private int maxRetries = 1;

        /**
         * Enables or disables AI enrichment.
         *
         * @param enabled {@code true} to enable AI integration
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Selects the AI provider.
         *
         * @param provider provider implementation to use
         * @return this builder
         */
        public Builder provider(AiProvider provider) {
            this.provider = provider;
            return this;
        }

        /**
         * Specifies the provider-specific model identifier.
         *
         * @param modelName name of the model to use
         * @return this builder
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets the base API endpoint used by the provider.
         *
         * @param baseUrl base URL of the provider API
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the API key used for authentication.
         *
         * @param apiKey API key value
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Specifies the environment variable that stores the API key.
         *
         * @param apiKeyEnv environment variable name
         * @return this builder
         */
        public Builder apiKeyEnv(String apiKeyEnv) {
            this.apiKeyEnv = apiKeyEnv;
            return this;
        }

        /**
         * Specifies an external taxonomy definition file.
         *
         * @param taxonomyFile path to taxonomy definition
         * @return this builder
         */
        public Builder taxonomyFile(Path taxonomyFile) {
            this.taxonomyFile = taxonomyFile;
            return this;
        }

        /**
         * Selects the built-in taxonomy mode.
         *
         * @param taxonomyMode taxonomy variant
         * @return this builder
         */
        public Builder taxonomyMode(TaxonomyMode taxonomyMode) {
            this.taxonomyMode = taxonomyMode;
            return this;
        }

        /**
         * Sets the maximum size of class source submitted to the AI provider.
         *
         * @param maxClassChars maximum allowed character count
         * @return this builder
         */
        public Builder maxClassChars(int maxClassChars) {
            this.maxClassChars = maxClassChars;
            return this;
        }

        /**
         * Sets the timeout applied to AI requests.
         *
         * @param timeout request timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the retry limit for AI requests.
         *
         * @param maxRetries retry count
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Builds the final immutable {@link AiOptions} configuration.
         *
         * <p>
         * If no base URL is explicitly supplied, a provider-specific default endpoint
         * is selected automatically.
         * </p>
         *
         * @return validated AI configuration
         */
        public AiOptions build() {
            AiProvider effectiveProvider = provider == null ? AiProvider.AUTO : provider;
            String effectiveBaseUrl = baseUrl;

            if (effectiveBaseUrl == null || effectiveBaseUrl.isBlank()) {
                effectiveBaseUrl = switch (effectiveProvider) {
                    case AUTO, OLLAMA -> "http://localhost:11434";
                    case OPENAI -> "https://api.openai.com";
                    case OPENROUTER -> "https://openrouter.ai/api";
                    case ANTHROPIC -> "https://api.anthropic.com";
                };
            }

            return new AiOptions(enabled, effectiveProvider, modelName, effectiveBaseUrl, apiKey, apiKeyEnv,
                    taxonomyFile, taxonomyMode, maxClassChars, timeout, maxRetries);
        }
    }
}
