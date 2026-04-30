package org.egothor.methodatlas.gui.dialog;

import org.egothor.methodatlas.ai.AiProvider;
import org.egothor.methodatlas.gui.model.AppSettings;
import org.egothor.methodatlas.gui.service.SettingsManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * Modal settings dialog covering AI provider configuration and UI preferences.
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

    // ── Components ────────────────────────────────────────────────────────

    private final JCheckBox aiEnabledBox = new JCheckBox("Enable AI enrichment");
    private final JComboBox<String> providerCombo = new JComboBox<>(PROVIDER_NAMES);
    private final JTextField modelField = new JTextField(24);
    private final JPasswordField apiKeyField = new JPasswordField(24);
    private final JTextField baseUrlField = new JTextField(24);
    private final JTextField apiVersionField = new JTextField(14);
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(90, 5, 600, 5));
    private final JSpinner retriesSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
    private final JCheckBox confidenceBox = new JCheckBox("Request confidence scores");
    private final JComboBox<String> themeCombo = new JComboBox<>(THEME_NAMES);

    private final AppSettings settings;
    private boolean confirmed = false;

    /**
     * @param owner    parent frame
     * @param settings settings to edit (modified in-place on Save)
     */
    public SettingsDialog(Frame owner, AppSettings settings) {
        super(owner, "Settings — MethodAtlas", true);
        this.settings = settings;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUi();
        populate();
        pack();
        setMinimumSize(new Dimension(520, 0));
        setLocationRelativeTo(owner);
    }

    // ── Construction ──────────────────────────────────────────────────────

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(12, 12, 8, 12));

        root.add(buildAiSection(), BorderLayout.CENTER);
        root.add(buildThemeSection(), BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton saveBtn = new JButton("Save");
        JButton cancelBtn = new JButton("Cancel");
        saveBtn.addActionListener(e -> onSave());
        cancelBtn.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(saveBtn);
        buttons.add(cancelBtn);
        buttons.add(saveBtn);

        JPanel outer = new JPanel(new BorderLayout(0, 8));
        outer.setBorder(new EmptyBorder(0, 0, 0, 0));
        outer.add(root, BorderLayout.CENTER);
        outer.add(buttons, BorderLayout.SOUTH);
        outer.setBorder(new EmptyBorder(12, 12, 12, 12));

        setContentPane(outer);

        // Auto-fill default model when provider changes
        providerCombo.addActionListener(e -> {
            int idx = providerCombo.getSelectedIndex();
            if (idx >= 0 && idx < DEFAULT_MODELS.length) {
                String current = modelField.getText().trim();
                // Only auto-fill if field is blank or matches a known default
                boolean isDefault = false;
                for (String dm : DEFAULT_MODELS) {
                    if (dm.equals(current)) { isDefault = true; break; }
                }
                if (current.isEmpty() || isDefault) {
                    modelField.setText(DEFAULT_MODELS[idx]);
                }
            }
        });

        // Toggle AI controls based on checkbox
        aiEnabledBox.addActionListener(e -> updateAiControlsEnabled());
    }

    private JPanel buildAiSection() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(titledBorder("AI Provider"));

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

    private JPanel buildThemeSection() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setBorder(titledBorder("Appearance"));
        panel.add(new JLabel("Theme:"));
        panel.add(themeCombo);
        panel.add(new JLabel(" (takes effect on next launch)"));
        return panel;
    }

    // ── Populate / Save ───────────────────────────────────────────────────

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

        SettingsManager.save(settings);
        confirmed = true;
        dispose();
    }

    /** @return {@code true} if the user clicked Save */
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
