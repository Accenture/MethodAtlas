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
 * platform-specific configuration directory.
 *
 * <h2>File location</h2>
 * <p>The settings file path is resolved once at class-load time using the
 * following priority order:</p>
 * <ol>
 *   <li>{@code %APPDATA%\MethodAtlasGUI\settings.json} — Windows, when
 *       the {@code APPDATA} environment variable is set</li>
 *   <li>{@code $XDG_CONFIG_HOME/methodatlas-gui/settings.json} — Linux /
 *       macOS, when the {@code XDG_CONFIG_HOME} variable is set</li>
 *   <li>{@code ~/.methodatlas-gui/settings.json} — fallback on all
 *       platforms when neither variable is set</li>
 * </ol>
 * <p>The resolved path is exposed via {@link #getSettingsFile()} and is
 * constant for the lifetime of the JVM.</p>
 *
 * <h2>Error handling</h2>
 * <p>Both {@link #load()} and {@link #save(AppSettings)} log warnings and
 * continue rather than propagating {@link IOException}.  A failed load
 * returns factory-default settings; a failed save is silently dropped
 * after logging.</p>
 *
 * @see AppSettings
 */
public final class SettingsManager {

    private static final Logger LOG = Logger.getLogger(SettingsManager.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path SETTINGS_FILE = resolveSettingsPath();

    private SettingsManager() {}

    /**
     * Returns the path of the JSON file used by {@link #load()} and
     * {@link #save(AppSettings)}.
     *
     * <p>The path is resolved once at class-load time according to the
     * platform-specific rules described in the class-level documentation,
     * and does not change for the lifetime of the JVM.  The file may or may
     * not exist when this method is called.</p>
     *
     * @return absolute path to the settings file; never {@code null}
     */
    public static Path getSettingsFile() { return SETTINGS_FILE; }

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
     * Loads application settings from the settings file.
     *
     * <p>If the file does not exist, or if it cannot be parsed, this method
     * logs a warning and returns a fresh {@link AppSettings} object
     * initialised to all default values.</p>
     *
     * @return application settings loaded from disk, or factory defaults on
     *         any read or parse error; never {@code null}
     */
    public static AppSettings load() {
        if (Files.exists(SETTINGS_FILE)) {
            try {
                return MAPPER.readValue(SETTINGS_FILE.toFile(), AppSettings.class);
            } catch (IOException e) {
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING, "Cannot read settings from " + SETTINGS_FILE + "; using defaults", e);
                }
            }
        }
        return new AppSettings();
    }

    /**
     * Persists the given settings to the settings file.
     *
     * <p>The parent directory is created if it does not yet exist.  If the
     * write fails for any reason, a warning is logged and the method returns
     * normally without propagating the exception.</p>
     *
     * @param settings settings object to serialise; must not be {@code null}
     */
    public static void save(AppSettings settings) {
        try {
            Files.createDirectories(SETTINGS_FILE.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(SETTINGS_FILE.toFile(), settings);
        } catch (IOException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Cannot save settings to " + SETTINGS_FILE, e);
            }
        }
    }
}
