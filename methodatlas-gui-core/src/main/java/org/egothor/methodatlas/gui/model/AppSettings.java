package org.egothor.methodatlas.gui.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
@SuppressWarnings("PMD.DataClass")
public final class AppSettings {

    // ── AI profiles ───────────────────────────────────────────────────────

    private List<AiProfile> profiles = new ArrayList<>(List.of(new AiProfile()));
    private String activeProfile = "Default";

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

    // ── Audit ─────────────────────────────────────────────────────────────

    /**
     * Name of the operator who reviews and approves tag decisions.
     * Written into the {@code note} field of each override YAML entry and
     * the evidence CSV produced on Save All Changes.
     * An empty string means the field is omitted from those records.
     */
    private String operatorName = "";

    // ── UI ────────────────────────────────────────────────────────────────

    private String themeClass = "com.formdev.flatlaf.FlatIntelliJLaf";
    private String lastDirectory = "";
    private int windowWidth = 1400;
    private int windowHeight = 900;
    private int leftSplitPosition = 400;
    private int rightSplitPosition = -1;

    // ── AI profile getters / setters ─────────────────────────────────────

    /**
     * Returns the list of all named AI provider profiles.
     *
     * <p>Each profile bundles a complete set of AI inference parameters under a
     * human-readable name.  The list always contains at least one entry; a
     * {@code "Default"} profile is inserted automatically when the list would
     * otherwise be empty.  Default: a single profile with name
     * {@code "Default"} and AI enrichment disabled.</p>
     *
     * @return mutable live list of profiles; never {@code null} or empty
     * @see #setProfiles(List)
     * @see #getActiveProfile()
     */
    public List<AiProfile> getProfiles() { return profiles; }

    /**
     * Replaces the list of named AI provider profiles.
     *
     * <p>The supplied list is copied defensively.  If the list is empty a
     * single default profile is added so that the application always has at
     * least one profile to work with.</p>
     *
     * @param profiles replacement profile list; must not be {@code null}
     * @see #getProfiles()
     */
    public void setProfiles(List<AiProfile> profiles) {
        this.profiles = new ArrayList<>(profiles);
        if (this.profiles.isEmpty()) {
            this.profiles.add(new AiProfile());
        }
    }

    /**
     * Returns the name of the currently active AI profile.
     *
     * <p>The active profile is used by the analysis service during a scan.
     * Default: {@code "Default"}.</p>
     *
     * @return active profile name; never {@code null}
     * @see #setActiveProfileName(String)
     * @see #getActiveProfile()
     */
    public String getActiveProfileName() { return activeProfile; }

    /**
     * Sets the name of the currently active AI profile.
     *
     * <p>If no profile with this name exists in {@link #getProfiles()}, the
     * first profile in the list is used as a fallback.</p>
     *
     * @param activeProfile profile name; must not be {@code null}
     * @see #getActiveProfileName()
     */
    public void setActiveProfileName(String activeProfile) { this.activeProfile = activeProfile; }

    /**
     * Returns the {@link AiProfile} whose name matches
     * {@link #getActiveProfileName()}.
     *
     * <p>Falls back to the first profile in the list when no name match is
     * found.  A default profile is created and added automatically when the
     * list is empty.</p>
     *
     * @return active profile; never {@code null}
     * @see #getActiveProfileName()
     */
    @JsonIgnore
    public AiProfile getActiveProfile() {
        if (profiles.isEmpty()) {
            AiProfile def = new AiProfile();
            profiles.add(def);
            return def;
        }
        return profiles.stream()
                .filter(p -> p.getName().equals(activeProfile))
                .findFirst()
                .orElse(profiles.get(0));
    }

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

    // ── Audit getters / setters ───────────────────────────────────────────

    /**
     * Returns the operator name included in audit records.
     *
     * <p>An empty string (the default) means the operator identity is not
     * recorded.  In regulated environments this should be set to the
     * reviewer's name or identifier.</p>
     *
     * @return operator name, or an empty string; never {@code null}
     * @see #setOperatorName(String)
     */
    public String getOperatorName() { return operatorName; }

    /**
     * Sets the operator name to be included in audit records.
     *
     * @param operatorName operator name, or an empty string to omit it;
     *                     must not be {@code null}
     * @see #getOperatorName()
     */
    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName == null ? "" : operatorName;
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
