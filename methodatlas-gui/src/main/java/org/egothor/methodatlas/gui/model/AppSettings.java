package org.egothor.methodatlas.gui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Serialisable application settings persisted between sessions.
 *
 * <p>All fields use mutable JavaBean conventions so that Jackson can
 * deserialise them without constructor arguments.</p>
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

    private List<String> fileSuffixes = new ArrayList<>(List.of("Test.java"));
    private List<String> testAnnotations = new ArrayList<>(
            List.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate"));

    // ── UI ────────────────────────────────────────────────────────────────

    private String themeClass = "com.formdev.flatlaf.FlatIntelliJLaf";
    private String lastDirectory = "";
    private int windowWidth = 1400;
    private int windowHeight = 900;
    private int leftSplitPosition = 400;
    private int rightSplitPosition = -1;

    // ── Getters / setters ─────────────────────────────────────────────────

    /** @return whether AI enrichment is enabled */
    public boolean isAiEnabled() { return aiEnabled; }
    /** @param aiEnabled {@code true} to enable AI */
    public void setAiEnabled(boolean aiEnabled) { this.aiEnabled = aiEnabled; }

    /** @return AI provider name (matches {@link org.egothor.methodatlas.ai.AiProvider} constant) */
    public String getAiProvider() { return aiProvider; }
    /** @param aiProvider provider constant name */
    public void setAiProvider(String aiProvider) { this.aiProvider = aiProvider; }

    /** @return model identifier */
    public String getAiModel() { return aiModel; }
    /** @param aiModel model identifier */
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }

    /** @return API key (may be blank) */
    public String getAiApiKey() { return aiApiKey; }
    /** @param aiApiKey API key */
    public void setAiApiKey(String aiApiKey) { this.aiApiKey = aiApiKey; }

    /** @return custom base URL or blank for the provider default */
    public String getAiBaseUrl() { return aiBaseUrl; }
    /** @param aiBaseUrl base URL */
    public void setAiBaseUrl(String aiBaseUrl) { this.aiBaseUrl = aiBaseUrl; }

    /** @return Azure OpenAI REST API version */
    public String getAiApiVersion() { return aiApiVersion; }
    /** @param aiApiVersion API version */
    public void setAiApiVersion(String aiApiVersion) { this.aiApiVersion = aiApiVersion; }

    /** @return request timeout in seconds */
    public int getAiTimeoutSeconds() { return aiTimeoutSeconds; }
    /** @param aiTimeoutSeconds timeout in seconds */
    public void setAiTimeoutSeconds(int aiTimeoutSeconds) { this.aiTimeoutSeconds = aiTimeoutSeconds; }

    /** @return maximum number of retries */
    public int getAiMaxRetries() { return aiMaxRetries; }
    /** @param aiMaxRetries retry limit */
    public void setAiMaxRetries(int aiMaxRetries) { this.aiMaxRetries = aiMaxRetries; }

    /** @return whether confidence scores are requested */
    public boolean isAiConfidence() { return aiConfidence; }
    /** @param aiConfidence {@code true} to request confidence scores */
    public void setAiConfidence(boolean aiConfidence) { this.aiConfidence = aiConfidence; }

    /** @return file suffixes used to select test source files */
    public List<String> getFileSuffixes() { return fileSuffixes; }
    /** @param fileSuffixes file suffix list */
    public void setFileSuffixes(List<String> fileSuffixes) {
        this.fileSuffixes = new ArrayList<>(fileSuffixes);
    }

    /** @return annotation names that mark a method as a test */
    public List<String> getTestAnnotations() { return testAnnotations; }
    /** @param testAnnotations annotation name list */
    public void setTestAnnotations(List<String> testAnnotations) {
        this.testAnnotations = new ArrayList<>(testAnnotations);
    }

    /** @return fully-qualified FlatLaf L&amp;F class name */
    public String getThemeClass() { return themeClass; }
    /** @param themeClass L&amp;F class name */
    public void setThemeClass(String themeClass) { this.themeClass = themeClass; }

    /** @return last directory path used for scanning */
    public String getLastDirectory() { return lastDirectory; }
    /** @param lastDirectory directory path */
    public void setLastDirectory(String lastDirectory) { this.lastDirectory = lastDirectory; }

    /** @return saved window width in pixels */
    public int getWindowWidth() { return windowWidth; }
    /** @param windowWidth window width */
    public void setWindowWidth(int windowWidth) { this.windowWidth = windowWidth; }

    /** @return saved window height in pixels */
    public int getWindowHeight() { return windowHeight; }
    /** @param windowHeight window height */
    public void setWindowHeight(int windowHeight) { this.windowHeight = windowHeight; }

    /** @return divider position between the results tree and the editor */
    public int getLeftSplitPosition() { return leftSplitPosition; }
    /** @param leftSplitPosition divider position */
    public void setLeftSplitPosition(int leftSplitPosition) { this.leftSplitPosition = leftSplitPosition; }

    /** @return divider position inside the right pane ({@code -1} for default) */
    public int getRightSplitPosition() { return rightSplitPosition; }
    /** @param rightSplitPosition divider position */
    public void setRightSplitPosition(int rightSplitPosition) { this.rightSplitPosition = rightSplitPosition; }
}
