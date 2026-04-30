package org.egothor.methodatlas.gui;

import com.formdev.flatlaf.FlatIntelliJLaf;
import org.egothor.methodatlas.gui.service.SettingsManager;

import javax.swing.*;

/**
 * Entry point for the MethodAtlas desktop GUI.
 *
 * <p>Sets up the Look and Feel from the persisted settings before creating
 * any Swing components, then opens the {@link MainWindow} on the Event
 * Dispatch Thread.</p>
 */
public final class MethodAtlasGuiApp {

    private MethodAtlasGuiApp() {}

    /**
     * Application entry point.
     *
     * @param args unused
     */
    public static void main(String[] args) {
        // Apply L&F before any Swing component is created
        applyLookAndFeel();

        SwingUtilities.invokeLater(() -> {
            // Enable FlatLaf's bundled font anti-aliasing system property
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("swing.aatext", "true");

            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }

    private static void applyLookAndFeel() {
        String lafClass = SettingsManager.load().getThemeClass();
        try {
            UIManager.setLookAndFeel(lafClass);
        } catch (Exception e) {
            // Fallback to FlatIntelliJLaf
            FlatIntelliJLaf.setup();
        }
    }
}
