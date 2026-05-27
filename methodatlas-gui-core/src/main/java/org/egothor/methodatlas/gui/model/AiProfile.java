package org.egothor.methodatlas.gui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Named AI provider configuration used by the MethodAtlas GUI.
 *
 * <p>Each profile bundles a complete set of AI inference parameters under a
 * human-readable name.  Multiple profiles can coexist in
 * {@link AppSettings#getProfiles()}, allowing the user to switch quickly
 * between configurations such as a fast local Ollama model, a precise
 * cloud-hosted model, or a project-specific provider.</p>
 *
 * <p>The active profile is identified by name via
 * {@link AppSettings#getActiveProfile()}.  All fields are initialised to the
 * same defaults as the former flat AI fields in {@code AppSettings} so that
 * existing installations without a saved profile list receive sensible
 * out-of-the-box behaviour.</p>
 *
 * <p>Instances follow mutable JavaBean conventions to allow Jackson
 * deserialisation without constructor arguments.
 * {@link JsonIgnoreProperties @JsonIgnoreProperties(ignoreUnknown = true)}
 * ensures forward compatibility when new fields are added.</p>
 *
 * @see AppSettings
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressWarnings("PMD.DataClass")
public final class AiProfile {

    private String  name             = "Default";
    private boolean enabled;
    private String  provider         = "AUTO";
    private String  model            = "qwen2.5-coder:7b";
    private String  apiKey           = "";
    private String  baseUrl          = "";
    private String  apiVersion       = "2024-02-01";
    private int     timeoutSeconds   = 90;
    private int     maxRetries       = 1;
    private boolean confidence;

    // ── Getters / setters ─────────────────────────────────────────────────

    /**
     * Returns the human-readable profile name displayed in the toolbar combo
     * and the Settings dialog.
     *
     * <p>Names must be unique within a single {@link AppSettings} instance.
     * Default: {@code "Default"}.</p>
     *
     * @return profile name; never {@code null}
     * @see #setName(String)
     */
    public String getName() { return name; }

    /**
     * Sets the profile name.
     *
     * @param name profile name; must not be {@code null} or blank
     * @see #getName()
     */
    public void setName(String name) { this.name = name; }

    /**
     * Returns whether AI enrichment is enabled for this profile.
     *
     * <p>When {@code false}, the analysis service skips the AI phase entirely
     * and completes as soon as file discovery finishes.  Default:
     * {@code false}.</p>
     *
     * @return {@code true} if the AI engine will be queried during analysis
     * @see #setEnabled(boolean)
     */
    public boolean isEnabled() { return enabled; }

    /**
     * Sets whether AI enrichment is enabled for this profile.
     *
     * @param enabled {@code true} to activate AI enrichment
     * @see #isEnabled()
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * Returns the name of the AI provider constant to use for inference.
     *
     * <p>The value must match one of the constants defined in
     * {@link org.egothor.methodatlas.ai.AiProvider}.  Default:
     * {@code "AUTO"}.</p>
     *
     * @return AI provider name; never {@code null}
     * @see #setProvider(String)
     */
    public String getProvider() { return provider; }

    /**
     * Sets the AI provider constant name.
     *
     * @param provider AI provider name matching an
     *                 {@link org.egothor.methodatlas.ai.AiProvider} constant;
     *                 must not be {@code null}
     * @see #getProvider()
     */
    public void setProvider(String provider) { this.provider = provider; }

    /**
     * Returns the AI model identifier used for inference.
     *
     * <p>The format is provider-specific (e.g.&nbsp;{@code "gpt-4o"} for
     * OpenAI, {@code "qwen2.5-coder:7b"} for Ollama).  Default:
     * {@code "qwen2.5-coder:7b"}.</p>
     *
     * @return model identifier; never {@code null}
     * @see #setModel(String)
     */
    public String getModel() { return model; }

    /**
     * Sets the AI model identifier.
     *
     * @param model model identifier; must not be {@code null}
     * @see #getModel()
     */
    public void setModel(String model) { this.model = model; }

    /**
     * Returns the API key used to authenticate with cloud-based providers.
     *
     * <p>An empty string means no key is configured, which is correct for
     * local providers such as Ollama.  Default: empty string.</p>
     *
     * @return API key, or empty string; never {@code null}
     * @see #setApiKey(String)
     */
    public String getApiKey() { return apiKey; }

    /**
     * Sets the API key.
     *
     * @param apiKey API key, or empty string; must not be {@code null}
     * @see #getApiKey()
     */
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    /**
     * Returns the custom base URL for the AI provider endpoint.
     *
     * <p>An empty string means the provider's built-in default URL is used.
     * Default: empty string.</p>
     *
     * @return base URL, or empty string; never {@code null}
     * @see #setBaseUrl(String)
     */
    public String getBaseUrl() { return baseUrl; }

    /**
     * Sets the custom base URL.
     *
     * @param baseUrl base URL, or empty string; must not be {@code null}
     * @see #getBaseUrl()
     */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    /**
     * Returns the REST API version string sent with Azure OpenAI requests.
     *
     * <p>Ignored for all providers other than {@code AZURE_OPENAI}.
     * Default: {@code "2024-02-01"}.</p>
     *
     * @return API version string; never {@code null}
     * @see #setApiVersion(String)
     */
    public String getApiVersion() { return apiVersion; }

    /**
     * Sets the REST API version string for Azure OpenAI.
     *
     * @param apiVersion API version string; must not be {@code null}
     * @see #getApiVersion()
     */
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

    /**
     * Returns the per-request timeout for AI calls, in seconds.
     *
     * <p>The timer resets for each retry attempt.  Default: {@code 90}.</p>
     *
     * @return timeout in seconds; always positive
     * @see #setTimeoutSeconds(int)
     */
    public int getTimeoutSeconds() { return timeoutSeconds; }

    /**
     * Sets the per-request timeout in seconds.
     *
     * @param timeoutSeconds timeout in seconds; must be positive
     * @see #getTimeoutSeconds()
     */
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    /**
     * Returns the maximum number of retry attempts for a failed AI call.
     *
     * <p>A value of {@code 0} means no retries.  Default: {@code 1}.</p>
     *
     * @return retry limit; non-negative
     * @see #setMaxRetries(int)
     */
    public int getMaxRetries() { return maxRetries; }

    /**
     * Sets the maximum retry count.
     *
     * @param maxRetries retry limit; must be non-negative
     * @see #getMaxRetries()
     */
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    /**
     * Returns whether the AI engine is asked to include confidence scores.
     *
     * <p>Default: {@code false}.</p>
     *
     * @return {@code true} if confidence scores are requested
     * @see #setConfidence(boolean)
     */
    public boolean isConfidence() { return confidence; }

    /**
     * Sets whether confidence scores are requested.
     *
     * @param confidence {@code true} to request confidence scores
     * @see #isConfidence()
     */
    public void setConfidence(boolean confidence) { this.confidence = confidence; }
}
