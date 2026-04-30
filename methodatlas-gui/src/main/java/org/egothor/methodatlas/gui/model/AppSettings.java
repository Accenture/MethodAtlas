package org.egothor.methodatlas.gui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialisable application settings that are persisted between sessions.
 *
 * <p>The settings object is loaded from a JSON file at startup by
 * {@link org.egothor.methodatlas.gui.service.SettingsManager#load()}, mutated
 * in response to user actions, and written back to disk by
 * {@link org.egothor.methodatlas.gui.service.SettingsManager#save(AppSettings)}.
 * All fields are initialised to safe defaults that allow the application to
 * function without any prior configuration.</p>
 *
 * <p>All fields follow mutable JavaBean conventions so that Jackson can
 * deserialise them without constructor arguments.  The
 * {@link JsonIgnoreProperties @JsonIgnoreProperties(ignoreUnknown = true)}
 * annotation ensures that settings files written by newer versions of the
 * application can be read by older versions without errors.</p>
 *
 * @see org.egothor.methodatlas.gui.service.SettingsManager
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AppSettings {

    // ── AI ────────────────────────────────────────────────────────────────

    private boolean aiEnabled = false;
    private String aiProvider = "AUTO";
    private String aiModel = "qwen2.5-coder:7b";
    private String aiApiKey = "";
    private String aiBaseUrl = "";
    private String aiApiVersion = "2024-02-01";
    private int aiTimeoutSeconds = 90;
    private int aiMaxRetries = 1;
    private boolean aiConfidence = false;

    // ── Discovery ─────────────────────────────────────────────────────────

    /**
     * Per-plugin file-mask overrides.
     * Absent key → plugin uses its own built-in default suffixes.
     */
    private Map<String, List<String>> pluginSuffixes = new LinkedHashMap<>();
    private List<String> testAnnotations = new ArrayList<>(
            List.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate"));

    // ── Plugins ───────────────────────────────────────────────────────────

    /**
     * Plugin IDs to include in scans.
     * An empty list means all available plugins are used.
     */
    private List<String> enabledPlugins = new ArrayList<>();

    // ── UI ────────────────────────────────────────────────────────────────

    private String themeClass = "com.formdev.flatlaf.FlatIntelliJLaf";
    private String lastDirectory = "";
    private int windowWidth = 1400;
    private int windowHeight = 900;
    private int leftSplitPosition = 400;
    private int rightSplitPosition = -1;

    // ── AI getters / setters ──────────────────────────────────────────────

    /**
     * Returns whether AI enrichment is enabled.
     *
     * <p>When {@code false}, the analysis service skips Phase 2 entirely
     * and completes as soon as discovery finishes.  Default: {@code false}.</p>
     *
     * @return {@code true} if the AI engine will be queried during analysis
     * @see #setAiEnabled(boolean)
     */
    public boolean isAiEnabled() { return aiEnabled; }

    /**
     * Sets whether AI enrichment is enabled.
     *
     * @param aiEnabled {@code true} to enable AI enrichment; {@code false}
     *                  to perform discovery only
     * @see #isAiEnabled()
     */
    public void setAiEnabled(boolean aiEnabled) { this.aiEnabled = aiEnabled; }

    /**
     * Returns the name of the AI provider constant to use for inference.
     *
     * <p>The value must match one of the constants defined in
     * {@link org.egothor.methodatlas.ai.AiProvider}.  Default:
     * {@code "AUTO"}, which tries a local Ollama instance first and falls
     * back to the configured API key.</p>
     *
     * @return AI provider name; never {@code null}
     * @see #setAiProvider(String)
     */
    public String getAiProvider() { return aiProvider; }

    /**
     * Sets the name of the AI provider constant to use for inference.
     *
     * @param aiProvider AI provider name matching an
     *                   {@link org.egothor.methodatlas.ai.AiProvider} constant;
     *                   must not be {@code null}
     * @see #getAiProvider()
     */
    public void setAiProvider(String aiProvider) { this.aiProvider = aiProvider; }

    /**
     * Returns the AI model identifier used for inference.
     *
     * <p>The format is provider-specific, for example {@code "gpt-4o"} for
     * OpenAI or {@code "qwen2.5-coder:7b"} for Ollama.  Default:
     * {@code "qwen2.5-coder:7b"}.</p>
     *
     * @return model identifier; never {@code null}
     * @see #setAiModel(String)
     */
    public String getAiModel() { return aiModel; }

    /**
     * Sets the AI model identifier used for inference.
     *
     * @param aiModel model identifier; must not be {@code null}
     * @see #getAiModel()
     */
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }

    /**
     * Returns the API key used to authenticate with cloud-based AI providers.
     *
     * <p>An empty string means no key has been configured, which is correct
     * for local providers such as Ollama.  Default: empty string.</p>
     *
     * @return API key, or an empty string if none is configured; never
     *         {@code null}
     * @see #setAiApiKey(String)
     */
    public String getAiApiKey() { return aiApiKey; }

    /**
     * Sets the API key used to authenticate with cloud-based AI providers.
     *
     * @param aiApiKey API key, or an empty string for providers that do not
     *                 require authentication; must not be {@code null}
     * @see #getAiApiKey()
     */
    public void setAiApiKey(String aiApiKey) { this.aiApiKey = aiApiKey; }

    /**
     * Returns the custom base URL for the AI provider endpoint.
     *
     * <p>An empty string means the provider's built-in default URL is used.
     * This setting is useful for self-hosted instances or API-compatible
     * proxies.  Default: empty string.</p>
     *
     * @return base URL, or an empty string to use the provider default;
     *         never {@code null}
     * @see #setAiBaseUrl(String)
     */
    public String getAiBaseUrl() { return aiBaseUrl; }

    /**
     * Sets the custom base URL for the AI provider endpoint.
     *
     * @param aiBaseUrl base URL, or an empty string to use the provider
     *                  default; must not be {@code null}
     * @see #getAiBaseUrl()
     */
    public void setAiBaseUrl(String aiBaseUrl) { this.aiBaseUrl = aiBaseUrl; }

    /**
     * Returns the REST API version string sent with Azure OpenAI requests.
     *
     * <p>This setting is ignored for all providers other than
     * {@code AZURE_OPENAI}.  Default: {@code "2024-02-01"}.</p>
     *
     * @return Azure OpenAI REST API version string; never {@code null}
     * @see #setAiApiVersion(String)
     */
    public String getAiApiVersion() { return aiApiVersion; }

    /**
     * Sets the REST API version string sent with Azure OpenAI requests.
     *
     * @param aiApiVersion API version string; must not be {@code null}
     * @see #getAiApiVersion()
     */
    public void setAiApiVersion(String aiApiVersion) { this.aiApiVersion = aiApiVersion; }

    /**
     * Returns the per-request timeout for AI calls, in seconds.
     *
     * <p>Requests that do not complete within this duration are aborted.
     * The timer resets for each retry attempt.  Default: {@code 90}.</p>
     *
     * @return timeout in seconds; always positive
     * @see #setAiTimeoutSeconds(int)
     */
    public int getAiTimeoutSeconds() { return aiTimeoutSeconds; }

    /**
     * Sets the per-request timeout for AI calls, in seconds.
     *
     * @param aiTimeoutSeconds timeout in seconds; must be positive
     * @see #getAiTimeoutSeconds()
     */
    public void setAiTimeoutSeconds(int aiTimeoutSeconds) { this.aiTimeoutSeconds = aiTimeoutSeconds; }

    /**
     * Returns the maximum number of retry attempts for a failed AI call.
     *
     * <p>A value of {@code 0} means no retries; the call either succeeds
     * on the first attempt or fails immediately.  Default: {@code 1}.</p>
     *
     * @return maximum retry count; non-negative
     * @see #setAiMaxRetries(int)
     */
    public int getAiMaxRetries() { return aiMaxRetries; }

    /**
     * Sets the maximum number of retry attempts for a failed AI call.
     *
     * @param aiMaxRetries retry limit; must be non-negative
     * @see #getAiMaxRetries()
     */
    public void setAiMaxRetries(int aiMaxRetries) { this.aiMaxRetries = aiMaxRetries; }

    /**
     * Returns whether the AI engine is asked to include confidence scores
     * alongside its tag suggestions.
     *
     * <p>When {@code true}, each suggestion carries a numeric confidence
     * value that can be used to filter or rank results.  Default:
     * {@code false}.</p>
     *
     * @return {@code true} if confidence scores are requested
     * @see #setAiConfidence(boolean)
     */
    public boolean isAiConfidence() { return aiConfidence; }

    /**
     * Sets whether the AI engine is asked to include confidence scores.
     *
     * @param aiConfidence {@code true} to request confidence scores
     * @see #isAiConfidence()
     */
    public void setAiConfidence(boolean aiConfidence) { this.aiConfidence = aiConfidence; }

    // ── Discovery getters / setters ───────────────────────────────────────

    /**
     * Returns the per-plugin file-mask overrides used during test-source
     * discovery.
     *
     * <p>The map key is a plugin identifier as returned by
     * {@link org.egothor.methodatlas.api.TestDiscovery#pluginId()}.  The
     * associated value is the list of file-name suffixes that the plugin will
     * use to select files (for example {@code ["Test.java", "IT.java"]} for
     * the {@code java} plugin).  Each suffix is matched against the end of a
     * file's name.</p>
     *
     * <p>A plugin whose ID is absent from this map, or whose list is empty,
     * receives no explicit configuration and falls back to its own built-in
     * default suffixes (for example the {@code java} plugin defaults to
     * {@code ["Test.java"]}, the {@code dotnet} plugin to {@code [".cs"]}).
     * This ensures that no cross-plugin file processing occurs when the map
     * is empty.</p>
     *
     * <p>Default: empty map (all plugins use their built-in defaults).</p>
     *
     * @return mutable live map of plugin-ID-to-suffix-list entries; never
     *         {@code null}
     * @see #setPluginSuffixes(Map)
     */
    public Map<String, List<String>> getPluginSuffixes() { return pluginSuffixes; }

    /**
     * Sets the per-plugin file-mask overrides used during test-source
     * discovery.
     *
     * <p>The supplied map is copied defensively.  Pass an empty map to let
     * every plugin use its own built-in default suffixes.</p>
     *
     * @param pluginSuffixes map of plugin-ID to suffix list; must not be
     *                       {@code null}
     * @see #getPluginSuffixes()
     */
    public void setPluginSuffixes(Map<String, List<String>> pluginSuffixes) {
        this.pluginSuffixes = new LinkedHashMap<>(pluginSuffixes);
    }

    /**
     * Returns the annotation simple names that identify a method as a test.
     *
     * <p>Used by the JVM and .NET discovery plugins to recognise test methods
     * without resolving imports.  Default:
     * {@code ["Test", "ParameterizedTest", "RepeatedTest", "TestFactory",
     * "TestTemplate"]}.</p>
     *
     * @return mutable live list of annotation names; never {@code null}
     * @see #setTestAnnotations(List)
     */
    public List<String> getTestAnnotations() { return testAnnotations; }

    /**
     * Sets the annotation simple names that identify a method as a test.
     *
     * <p>The supplied list is copied defensively.</p>
     *
     * @param testAnnotations annotation name list; must not be {@code null}
     * @see #getTestAnnotations()
     */
    public void setTestAnnotations(List<String> testAnnotations) {
        this.testAnnotations = new ArrayList<>(testAnnotations);
    }

    // ── Plugin getters / setters ──────────────────────────────────────────

    /**
     * Returns the IDs of the discovery plugins that will be used during a scan.
     *
     * <p>An empty list means that all plugins available on the classpath are
     * used.  A non-empty list acts as an allowlist: only plugins whose
     * {@link org.egothor.methodatlas.api.TestDiscovery#pluginId() pluginId()}
     * appears in this list are invoked.  Default: empty list (all plugins).</p>
     *
     * @return mutable live list of plugin IDs; never {@code null}
     * @see #setEnabledPlugins(List)
     */
    public List<String> getEnabledPlugins() { return enabledPlugins; }

    /**
     * Sets the IDs of the discovery plugins that will be used during a scan.
     *
     * <p>Pass an empty list to enable all available plugins.  The supplied
     * list is copied defensively.</p>
     *
     * @param enabledPlugins plugin ID allowlist, or an empty list to enable
     *                       all plugins; must not be {@code null}
     * @see #getEnabledPlugins()
     */
    public void setEnabledPlugins(List<String> enabledPlugins) {
        this.enabledPlugins = new ArrayList<>(enabledPlugins);
    }

    // ── UI getters / setters ──────────────────────────────────────────────

    /**
     * Returns the fully-qualified FlatLaf look-and-feel class name.
     *
     * <p>The theme is applied at application startup; changing this setting
     * takes effect on the next launch.  Default:
     * {@code "com.formdev.flatlaf.FlatIntelliJLaf"}.</p>
     *
     * @return L&amp;F class name; never {@code null}
     * @see #setThemeClass(String)
     */
    public String getThemeClass() { return themeClass; }

    /**
     * Sets the fully-qualified FlatLaf look-and-feel class name.
     *
     * @param themeClass L&amp;F class name; must not be {@code null}
     * @see #getThemeClass()
     */
    public void setThemeClass(String themeClass) { this.themeClass = themeClass; }

    /**
     * Returns the last directory path the user opened for scanning.
     *
     * <p>This value is restored in the directory field when the application
     * starts.  Default: empty string (no previous directory).</p>
     *
     * @return last-used directory path, or an empty string if none has been
     *         set; never {@code null}
     * @see #setLastDirectory(String)
     */
    public String getLastDirectory() { return lastDirectory; }

    /**
     * Sets the last directory path used for scanning.
     *
     * @param lastDirectory directory path, or an empty string to clear the
     *                      saved value; must not be {@code null}
     * @see #getLastDirectory()
     */
    public void setLastDirectory(String lastDirectory) { this.lastDirectory = lastDirectory; }

    /**
     * Returns the saved main window width in pixels.
     *
     * <p>Default: {@code 1400}.</p>
     *
     * @return window width in pixels; always positive
     * @see #setWindowWidth(int)
     */
    public int getWindowWidth() { return windowWidth; }

    /**
     * Sets the main window width in pixels.
     *
     * @param windowWidth window width in pixels; must be positive
     * @see #getWindowWidth()
     */
    public void setWindowWidth(int windowWidth) { this.windowWidth = windowWidth; }

    /**
     * Returns the saved main window height in pixels.
     *
     * <p>Default: {@code 900}.</p>
     *
     * @return window height in pixels; always positive
     * @see #setWindowHeight(int)
     */
    public int getWindowHeight() { return windowHeight; }

    /**
     * Sets the main window height in pixels.
     *
     * @param windowHeight window height in pixels; must be positive
     * @see #getWindowHeight()
     */
    public void setWindowHeight(int windowHeight) { this.windowHeight = windowHeight; }

    /**
     * Returns the saved divider position between the results tree and the
     * right-hand editor/tag pane.
     *
     * <p>The value is the pixel distance from the left edge of the main
     * split pane to the divider.  Default: {@code 400}.</p>
     *
     * @return divider position in pixels; always non-negative
     * @see #setLeftSplitPosition(int)
     */
    public int getLeftSplitPosition() { return leftSplitPosition; }

    /**
     * Sets the divider position between the results tree and the right-hand
     * editor/tag pane.
     *
     * @param leftSplitPosition divider position in pixels; must be
     *                          non-negative
     * @see #getLeftSplitPosition()
     */
    public void setLeftSplitPosition(int leftSplitPosition) { this.leftSplitPosition = leftSplitPosition; }

    /**
     * Returns the saved divider position inside the right pane, between the
     * source editor and the tag editor.
     *
     * <p>A value of {@code -1} means the position is determined automatically
     * from the preferred sizes of the two panels.  Default: {@code -1}.</p>
     *
     * @return divider position in pixels, or {@code -1} for automatic
     * @see #setRightSplitPosition(int)
     */
    public int getRightSplitPosition() { return rightSplitPosition; }

    /**
     * Sets the divider position inside the right pane, between the source
     * editor and the tag editor.
     *
     * @param rightSplitPosition divider position in pixels, or {@code -1} to
     *                           let the layout manager choose
     * @see #getRightSplitPosition()
     */
    public void setRightSplitPosition(int rightSplitPosition) { this.rightSplitPosition = rightSplitPosition; }
}
