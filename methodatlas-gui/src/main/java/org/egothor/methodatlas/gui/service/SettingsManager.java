package org.egothor.methodatlas.gui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.egothor.methodatlas.gui.model.AppSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads and saves {@link AppSettings} to a JSON file in the user's
 * configuration directory.
 *
 * <p>The file is written to
 * {@code $XDG_CONFIG_HOME/methodatlas-gui/settings.json} on Linux/macOS
 * and {@code %APPDATA%\MethodAtlasGUI\settings.json} on Windows.
 * When neither variable is set the file falls back to
 * {@code ~/.methodatlas-gui/settings.json}.</p>
 */
public final class SettingsManager {

    private static final Logger LOG = Logger.getLogger(SettingsManager.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path SETTINGS_FILE = resolveSettingsPath();

    private SettingsManager() {}

    private static Path resolveSettingsPath() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "MethodAtlasGUI", "settings.json");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "methodatlas-gui", "settings.json");
        }
        return Path.of(System.getProperty("user.home"), ".methodatlas-gui", "settings.json");
    }

    /**
     * Loads settings from disk.
     *
     * <p>Returns default settings when the file does not exist or cannot
     * be read.</p>
     *
     * @return loaded or default settings; never {@code null}
     */
    public static AppSettings load() {
        if (Files.exists(SETTINGS_FILE)) {
            try {
                return MAPPER.readValue(SETTINGS_FILE.toFile(), AppSettings.class);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Cannot read settings from " + SETTINGS_FILE + "; using defaults", e);
            }
        }
        return new AppSettings();
    }

    /**
     * Persists settings to disk.
     *
     * @param settings settings to save
     */
    public static void save(AppSettings settings) {
        try {
            Files.createDirectories(SETTINGS_FILE.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(SETTINGS_FILE.toFile(), settings);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot save settings to " + SETTINGS_FILE, e);
        }
    }
}
