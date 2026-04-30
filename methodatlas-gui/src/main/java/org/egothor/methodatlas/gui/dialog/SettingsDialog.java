package org.egothor.methodatlas.gui.dialog;

import org.egothor.methodatlas.ai.AiProvider;
import org.egothor.methodatlas.gui.model.AppSettings;
import org.egothor.methodatlas.gui.service.AnalysisService;
import org.egothor.methodatlas.gui.service.SettingsManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modal settings dialog covering AI provider configuration, plugin selection,
 * and UI preferences.
 *
 * <p>Changes are written to the model and persisted via {@link SettingsManager}
 * only when the user confirms with the <em>Save</em> button.  Closing or
 * clicking <em>Cancel</em> discards all changes.</p>
 */
public final class SettingsDialog extends JDialog {

    // ── Provider info ─────────────────────────────────────────────────────

    private static final String[] PROVIDER_NAMES = {
            "AUTO (Ollama → API key fallback)",
            "OLLAMA (local Ollama instance)",
            "OPENAI (ChatGPT API)",
            "ANTHROPIC (Claude API)",
            "AZURE_OPENAI (Azure OpenAI Service)",
            "GROQ (Groq LPU cloud)",
            "XAI (Grok / xAI)",
            "GITHUB_MODELS (GitHub free tier)",
            "MISTRAL (Mistral AI)",
            "OPENROUTER (multi-model gateway)"
    };

    private static final AiProvider[] PROVIDERS = {
            AiProvider.AUTO, AiProvider.OLLAMA, AiProvider.OPENAI, AiProvider.ANTHROPIC,
            AiProvider.AZURE_OPENAI, AiProvider.GROQ, AiProvider.XAI,
            AiProvider.GITHUB_MODELS, AiProvider.MISTRAL, AiProvider.OPENROUTER
    };

    private static final String[] DEFAULT_MODELS = {
            "qwen2.5-coder:7b",    // AUTO
            "qwen2.5-coder:7b",    // OLLAMA
            "gpt-4o",              // OPENAI
            "claude-sonnet-4-6",   // ANTHROPIC
            "gpt-4o",              // AZURE_OPENAI
            "llama-3.3-70b-versatile", // GROQ
            "grok-2-latest",       // XAI
            "gpt-4o",              // GITHUB_MODELS
            "mistral-large-latest",// MISTRAL
            "openai/gpt-4o"        // OPENROUTER
    };

    private static final String[] THEME_CLASSES = {
            "com.formdev.flatlaf.FlatIntelliJLaf",
            "com.formdev.flatlaf.FlatDarkLaf",
            "com.formdev.flatlaf.FlatLightLaf",
            "com.formdev.flatlaf.FlatDarculaLaf"
    };

    private static final String[] THEME_NAMES = {
            "IntelliJ Light", "Flat Dark", "Flat Light", "Darcula"
    };

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    // ── AI components ─────────────────────────────────────────────────────

    private final JCheckBox aiEnabledBox = new JCheckBox("Enable AI enrichment");
    private final JComboBox<String> providerCombo = new JComboBox<>(PROVIDER_NAMES);
    private final JTextField modelField = new JTextField(24);
    private final JPasswordField apiKeyField = new JPasswordField(24);
    private final JTextField baseUrlField = new JTextField(24);
    private final JTextField apiVersionField = new JTextField(14);
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(90, 5, 600, 5));
    private final JSpinner retriesSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
    private final JCheckBox confidenceBox = new JCheckBox("Request confidence scores");

    // ── Plugin components ─────────────────────────────────────────────────

    /** Maps plugin ID → enable/disable checkbox; populated at construction time. */
    private final Map<String, JCheckBox> pluginBoxes = new LinkedHashMap<>();

    /** Maps plugin ID → file-mask text field; populated at construction time. */
    private final Map<String, JTextField> pluginSuffixFields = new LinkedHashMap<>();

    // ── Appearance ────────────────────────────────────────────────────────

    private final JComboBox<String> themeCombo = new JComboBox<>(THEME_NAMES);

    // ── State ─────────────────────────────────────────────────────────────

    private final AppSettings settings;
    private boolean confirmed = false;

    /**
     * Constructs the settings dialog, populates all fields from the
     * current settings, and centres the dialog over {@code owner}.
     *
     * <p>The dialog is modal.  Changes are applied to {@code settings} and
     * persisted to disk only when the user clicks <em>Save</em>.  Clicking
     * <em>Cancel</em> or closing the window leaves {@code settings}
     * unchanged.  After {@code setVisible(true)} returns, check
     * {@link #isConfirmed()} to determine which action the user took.</p>
     *
     * <p>Must be called on the Swing Event Dispatch Thread.</p>
     *
     * @param owner    parent frame used for modal blocking and positioning;
     *                 must not be {@code null}
     * @param settings settings object whose fields are pre-populated into
     *                 the form and updated on save; must not be {@code null}
     */
    public SettingsDialog(Frame owner, AppSettings settings) {
        super(owner, "Settings — MethodAtlas", true);
        this.settings = settings;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUi();
        populate();
        pack();
        setMinimumSize(new Dimension(540, 0));
        setLocationRelativeTo(owner);
    }

    // ── Construction ──────────────────────────────────────────────────────

    private void buildUi() {
        JPanel centre = new JPanel();
        centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
        centre.setBorder(new EmptyBorder(12, 12, 8, 12));
        centre.add(buildAiSection());
        centre.add(Box.createVerticalStrut(6));
        centre.add(buildPluginsSection());
        centre.add(Box.createVerticalStrut(6));
        centre.add(buildThemeSection());
        centre.add(Box.createVerticalStrut(6));
        centre.add(buildConfigPathSection());

        JPanel buttons = buildButtonRow();

        JPanel outer = new JPanel(new BorderLayout(0, 8));
        outer.setBorder(new EmptyBorder(0, 0, 12, 0));
        outer.add(new JScrollPane(centre,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        outer.add(buttons, BorderLayout.SOUTH);

        setContentPane(outer);

        // Auto-fill default model when provider changes
        providerCombo.addActionListener(e -> {
            int idx = providerCombo.getSelectedIndex();
            if (idx >= 0 && idx < DEFAULT_MODELS.length) {
                String current = modelField.getText().trim();
                boolean isDefault = false;
                for (String dm : DEFAULT_MODELS) {
                    if (dm.equals(current)) { isDefault = true; break; }
                }
                if (current.isEmpty() || isDefault) {
                    modelField.setText(DEFAULT_MODELS[idx]);
                }
            }
        });

        aiEnabledBox.addActionListener(e -> updateAiControlsEnabled());
    }

    private JPanel buildAiSection() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(titledBorder("AI Provider"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(aiEnabledBox, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints lc = labelConstraints();
        GridBagConstraints fc = fieldConstraints();

        int row = 0;
        addRow(grid, "Provider:", providerCombo, lc, fc, row++);
        addRow(grid, "Model:", modelField, lc, fc, row++);
        addRow(grid, "API Key:", apiKeyField, lc, fc, row++);
        addRow(grid, "Base URL (optional):", baseUrlField, lc, fc, row++);
        addRow(grid, "Azure API Version:", apiVersionField, lc, fc, row++);
        addRow(grid, "Timeout (seconds):", timeoutSpinner, lc, fc, row++);
        addRow(grid, "Max retries:", retriesSpinner, lc, fc, row++);

        GridBagConstraints cc = new GridBagConstraints();
        cc.gridx = 1; cc.gridy = row; cc.anchor = GridBagConstraints.WEST;
        grid.add(confidenceBox, cc);

        panel.add(grid, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildPluginsSection() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(titledBorder("Discovery Plugins"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        List<String> available = AnalysisService.availablePluginIds();
        if (available.isEmpty()) {
            panel.add(new JLabel("  No discovery plugins detected on classpath."), BorderLayout.CENTER);
            return panel;
        }

        JPanel grid = new JPanel(new GridBagLayout());

        // Header
        GridBagConstraints hc = new GridBagConstraints();
        hc.gridy = 0; hc.anchor = GridBagConstraints.WEST;
        hc.insets = new Insets(0, 2, 4, 8);
        hc.gridx = 0; grid.add(new JLabel("Enable"), hc);
        hc.gridx = 1; grid.add(new JLabel("Plugin"), hc);
        hc.gridx = 2;
        hc.insets = new Insets(0, 2, 4, 0);
        hc.weightx = 1.0; hc.fill = GridBagConstraints.HORIZONTAL;
        grid.add(new JLabel("File masks  (comma-separated; blank = plugin built-in default)"), hc);

        GridBagConstraints checkC = new GridBagConstraints();
        checkC.anchor = GridBagConstraints.CENTER;
        checkC.insets = new Insets(1, 2, 1, 8);

        GridBagConstraints idC = new GridBagConstraints();
        idC.anchor = GridBagConstraints.WEST;
        idC.insets = new Insets(1, 0, 1, 8);

        GridBagConstraints fieldC = new GridBagConstraints();
        fieldC.anchor = GridBagConstraints.WEST;
        fieldC.fill = GridBagConstraints.HORIZONTAL;
        fieldC.weightx = 1.0;
        fieldC.insets = new Insets(1, 0, 1, 0);

        int row = 1;
        for (String id : available) {
            JCheckBox box = new JCheckBox();
            box.setToolTipText("Include plugin '" + id + "' in scans");
            pluginBoxes.put(id, box);

            JTextField suffixField = new JTextField(20);
            suffixField.putClientProperty("JTextField.placeholderText", "plugin default");
            suffixField.setToolTipText(
                    "File masks for plugin '" + id + "' (comma-separated, e.g. Test.java, IT.java)");
            pluginSuffixFields.put(id, suffixField);

            checkC.gridx = 0; checkC.gridy = row;
            grid.add(box, checkC);
            idC.gridx = 1; idC.gridy = row;
            grid.add(new JLabel(id), idC);
            fieldC.gridx = 2; fieldC.gridy = row;
            grid.add(suffixField, fieldC);
            row++;
        }

        panel.add(grid, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildThemeSection() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setBorder(titledBorder("Appearance"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(new JLabel("Theme:"));
        panel.add(themeCombo);
        panel.add(new JLabel(" (takes effect on next launch)"));
        return panel;
    }

    private JPanel buildConfigPathSection() {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setBorder(titledBorder("Configuration File"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        String path = SettingsManager.getSettingsFile().toString();
        JTextField pathField = new JTextField(path);
        pathField.setEditable(false);
        pathField.setFont(pathField.getFont().deriveFont(Font.PLAIN, 11f));
        pathField.setForeground(UIManager.getColor("Label.disabledForeground"));

        JButton openDirButton = new JButton("Open folder");
        openDirButton.addActionListener(e -> openContainingFolder(path));

        panel.add(pathField, BorderLayout.CENTER);
        panel.add(openDirButton, BorderLayout.EAST);
        return panel;
    }

    private JPanel buildButtonRow() {
        JButton resetBtn = new JButton("Reset to Defaults");
        JButton saveBtn = new JButton("Save");
        JButton cancelBtn = new JButton("Cancel");

        resetBtn.setToolTipText("Restore all settings to their built-in defaults");
        resetBtn.addActionListener(e -> onReset());
        saveBtn.addActionListener(e -> onSave());
        cancelBtn.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(saveBtn);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(new EmptyBorder(0, 12, 0, 12));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.add(resetBtn);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(cancelBtn);
        right.add(saveBtn);

        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);
        return buttons;
    }

    // ── Populate / Save / Reset ───────────────────────────────────────────

    private void populate() {
        aiEnabledBox.setSelected(settings.isAiEnabled());

        String provName = settings.getAiProvider();
        for (int i = 0; i < PROVIDERS.length; i++) {
            if (PROVIDERS[i].name().equals(provName)) {
                providerCombo.setSelectedIndex(i);
                break;
            }
        }

        modelField.setText(settings.getAiModel());
        apiKeyField.setText(settings.getAiApiKey());
        baseUrlField.setText(settings.getAiBaseUrl());
        apiVersionField.setText(settings.getAiApiVersion());
        timeoutSpinner.setValue(settings.getAiTimeoutSeconds());
        retriesSpinner.setValue(settings.getAiMaxRetries());
        confidenceBox.setSelected(settings.isAiConfidence());

        String themeClass = settings.getThemeClass();
        for (int i = 0; i < THEME_CLASSES.length; i++) {
            if (THEME_CLASSES[i].equals(themeClass)) {
                themeCombo.setSelectedIndex(i);
                break;
            }
        }

        List<String> enabled = settings.getEnabledPlugins();
        for (Map.Entry<String, JCheckBox> entry : pluginBoxes.entrySet()) {
            entry.getValue().setSelected(enabled.isEmpty() || enabled.contains(entry.getKey()));
        }

        Map<String, List<String>> pluginSuffixes = settings.getPluginSuffixes();
        for (Map.Entry<String, JTextField> entry : pluginSuffixFields.entrySet()) {
            List<String> masks = pluginSuffixes.get(entry.getKey());
            entry.getValue().setText(masks != null && !masks.isEmpty()
                    ? String.join(", ", masks) : "");
        }

        updateAiControlsEnabled();
    }

    private void onSave() {
        settings.setAiEnabled(aiEnabledBox.isSelected());
        int idx = providerCombo.getSelectedIndex();
        settings.setAiProvider(idx >= 0 ? PROVIDERS[idx].name() : "AUTO");
        settings.setAiModel(modelField.getText().trim());
        settings.setAiApiKey(new String(apiKeyField.getPassword()));
        settings.setAiBaseUrl(baseUrlField.getText().trim());
        settings.setAiApiVersion(apiVersionField.getText().trim());
        settings.setAiTimeoutSeconds((int) timeoutSpinner.getValue());
        settings.setAiMaxRetries((int) retriesSpinner.getValue());
        settings.setAiConfidence(confidenceBox.isSelected());

        int themeIdx = themeCombo.getSelectedIndex();
        if (themeIdx >= 0) settings.setThemeClass(THEME_CLASSES[themeIdx]);

        // Enabled plugins: empty list = all enabled; explicit list = filtered
        List<String> enabledPlugins = new ArrayList<>();
        boolean allChecked = pluginBoxes.values().stream().allMatch(JCheckBox::isSelected);
        if (!allChecked) {
            pluginBoxes.forEach((id, box) -> { if (box.isSelected()) enabledPlugins.add(id); });
        }
        settings.setEnabledPlugins(enabledPlugins);

        // Per-plugin file masks: absent = plugin uses its own built-in default
        Map<String, List<String>> pluginSuffixes = new LinkedHashMap<>();
        pluginSuffixFields.forEach((id, field) -> {
            String text = field.getText().trim();
            if (!text.isEmpty()) {
                List<String> masks = new ArrayList<>();
                for (String token : text.split(",")) {
                    String m = token.trim();
                    if (!m.isEmpty()) masks.add(m);
                }
                if (!masks.isEmpty()) pluginSuffixes.put(id, masks);
            }
        });
        settings.setPluginSuffixes(pluginSuffixes);

        SettingsManager.save(settings);
        confirmed = true;
        dispose();
    }

    private void onReset() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Reset all settings to built-in defaults?\nThe settings file will be overwritten on Save.",
                "Reset to Defaults", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        AppSettings defaults = new AppSettings();
        // Copy defaults into fields without touching the live settings object yet
        aiEnabledBox.setSelected(defaults.isAiEnabled());
        for (int i = 0; i < PROVIDERS.length; i++) {
            if (PROVIDERS[i].name().equals(defaults.getAiProvider())) {
                providerCombo.setSelectedIndex(i);
                break;
            }
        }
        modelField.setText(defaults.getAiModel());
        apiKeyField.setText(defaults.getAiApiKey());
        baseUrlField.setText(defaults.getAiBaseUrl());
        apiVersionField.setText(defaults.getAiApiVersion());
        timeoutSpinner.setValue(defaults.getAiTimeoutSeconds());
        retriesSpinner.setValue(defaults.getAiMaxRetries());
        confidenceBox.setSelected(defaults.isAiConfidence());
        for (int i = 0; i < THEME_CLASSES.length; i++) {
            if (THEME_CLASSES[i].equals(defaults.getThemeClass())) {
                themeCombo.setSelectedIndex(i);
                break;
            }
        }
        // Re-enable all plugin checkboxes and clear suffix overrides
        pluginBoxes.values().forEach(b -> b.setSelected(true));
        pluginSuffixFields.values().forEach(f -> f.setText(""));
        updateAiControlsEnabled();
    }

    /**
     * Returns whether the user confirmed the dialog by clicking
     * <em>Save</em>.
     *
     * <p>This method returns {@code false} both before the dialog is shown
     * and when it was dismissed via <em>Cancel</em> or the window close
     * button.</p>
     *
     * @return {@code true} if and only if the user clicked <em>Save</em>
     */
    public boolean isConfirmed() { return confirmed; }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void updateAiControlsEnabled() {
        boolean on = aiEnabledBox.isSelected();
        providerCombo.setEnabled(on);
        modelField.setEnabled(on);
        apiKeyField.setEnabled(on);
        baseUrlField.setEnabled(on);
        apiVersionField.setEnabled(on);
        timeoutSpinner.setEnabled(on);
        retriesSpinner.setEnabled(on);
        confidenceBox.setEnabled(on);
    }

    private static void openContainingFolder(String filePath) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(filePath).getParent();
            if (dir != null && java.nio.file.Files.isDirectory(dir)) {
                Desktop.getDesktop().open(dir.toFile());
            }
        } catch (Exception ex) {
            // Desktop.open not supported on all platforms — silently ignore
        }
    }

    private static void addRow(JPanel grid, String labelText, JComponent field,
            GridBagConstraints lc, GridBagConstraints fc, int row) {
        lc.gridy = row;
        fc.gridy = row;
        grid.add(new JLabel(labelText), lc);
        grid.add(field, fc);
    }

    private static GridBagConstraints labelConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 8);
        return c;
    }

    private static GridBagConstraints fieldConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1; c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0; c.insets = new Insets(3, 0, 3, 0);
        return c;
    }

    private static TitledBorder titledBorder(String title) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title,
                TitledBorder.LEFT, TitledBorder.TOP);
    }
}
