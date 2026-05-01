package org.egothor.methodatlas.gui;

import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.gui.dialog.SettingsDialog;
import org.egothor.methodatlas.gui.model.AiProfile;
import org.egothor.methodatlas.gui.model.AnalysisModel;
import org.egothor.methodatlas.gui.model.AppSettings;
import org.egothor.methodatlas.gui.model.MethodEntry;
import org.egothor.methodatlas.gui.panel.ActivityPanel;
import org.egothor.methodatlas.gui.panel.EditorPanel;
import org.egothor.methodatlas.gui.panel.ScanPanel;
import org.egothor.methodatlas.gui.panel.StatusBar;
import org.egothor.methodatlas.gui.panel.TagEditorPanel;
import org.egothor.methodatlas.gui.service.AnalysisService;
import org.egothor.methodatlas.gui.service.AuditWriter;
import org.egothor.methodatlas.gui.service.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Main application window for MethodAtlas GUI.
 *
 * <p>Layout overview:</p>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │  Toolbar: Dir · Run · Cancel · Save All · Profile · Settings │
 * ├──────────────┬───────────────────────────────────────────────┤
 * │              │  Source editor (RSyntaxTextArea)               │
 * │  Results     ├───────────────────────────────────────────────┤
 * │  tree        │  Tag editor (AI chips + override + apply)      │
 * ├──────────────┴───────────────────────────────────────────────┤
 * │  Activity panel (hidden when idle, collapsible log)          │
 * ├──────────────────────────────────────────────────────────────┤
 * │  Status bar                                                  │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class MainWindow extends JFrame {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    // ── State ─────────────────────────────────────────────────────────────

    private final AppSettings settings;
    private boolean updatingProfileCombo = false;
    private final AnalysisModel model = new AnalysisModel();
    private AnalysisService currentService;

    // ── Toolbar controls ──────────────────────────────────────────────────

    private final JTextField dirField = new JTextField(32);
    private final JButton browseButton = new JButton("Browse…");
    private final JButton runButton = new JButton("▶  Run Analysis");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton saveAllButton = new JButton("💾  Save All Changes");
    private final JComboBox<String> profileCombo = new JComboBox<>();
    private final JButton settingsButton = new JButton("⚙  Settings");

    // ── Split panes ───────────────────────────────────────────────────────

    private JSplitPane rightSplit;

    // ── Panels ────────────────────────────────────────────────────────────

    private final ScanPanel scanPanel;
    private final EditorPanel editorPanel;
    private final TagEditorPanel tagEditorPanel;
    private final ActivityPanel activityPanel;
    private final StatusBar statusBar;

    /**
     * Constructs the main window, loads persisted settings, builds all
     * panels and the toolbar, and wires event listeners.
     *
     * <p>Must be called on the Swing Event Dispatch Thread.  The window is
     * not yet visible after construction; call {@link #setVisible(boolean)
     * setVisible(true)} to display it.</p>
     */
    public MainWindow() {
        super("MethodAtlas");

        settings = SettingsManager.load();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose();
            }
        });

        // Build panels (order matters: editor before tagEditor)
        scanPanel = new ScanPanel(model);
        editorPanel = new EditorPanel(model);
        tagEditorPanel = new TagEditorPanel(model, settings);
        activityPanel = new ActivityPanel(model);
        statusBar = new StatusBar(model);

        buildLayout();
        wireToolbar();
        wireModelObserver();
        restoreWindowState();
        applyLastDirectory();
        refreshProfileCombo();
        // Apply split positions after the window is realized and laid out.
        // invokeLater fires after setVisible(true) in MethodAtlasGuiApp's own
        // invokeLater, so the split pane has its actual height by then.
        SwingUtilities.invokeLater(this::initSplitPositions);
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private void buildLayout() {
        // Right pane: editor on top, tag editor on bottom.
        // Divider position is applied later in initSplitPositions() once the
        // window is realized, so setDividerLocation(double) works correctly.
        rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPanel, tagEditorPanel);
        rightSplit.setResizeWeight(0.8);
        rightSplit.setBorder(null);

        // Main split: results tree on left, right pane on right
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scanPanel, rightSplit);
        mainSplit.setResizeWeight(0.0);
        mainSplit.setBorder(null);
        mainSplit.setDividerLocation(settings.getLeftSplitPosition());

        // Persist split positions on resize
        mainSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                e -> settings.setLeftSplitPosition((int) e.getNewValue()));
        rightSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                e -> settings.setRightSplitPosition((int) e.getNewValue()));

        // South area: activity panel (collapsible, hides when idle) + status bar
        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(activityPanel, BorderLayout.NORTH);
        southPanel.add(statusBar, BorderLayout.SOUTH);

        getContentPane().add(buildToolbar(), BorderLayout.NORTH);
        getContentPane().add(mainSplit, BorderLayout.CENTER);
        getContentPane().add(southPanel, BorderLayout.SOUTH);
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, UIManager.getColor("Separator.foreground")));

        dirField.setToolTipText("Directory to scan for test sources");
        dirField.putClientProperty("JTextField.placeholderText", "Enter or browse to test source directory…");

        cancelButton.setEnabled(false);
        cancelButton.setToolTipText("Cancel running analysis");

        runButton.putClientProperty("JButton.buttonType", "default");

        saveAllButton.setEnabled(false);
        saveAllButton.setToolTipText(
                "Write all staged tag changes to disk (groups all methods per file to prevent line-number drift)");

        profileCombo.setToolTipText("Active AI provider profile");
        profileCombo.setPrototypeDisplayValue("Default Profile XXXX");

        bar.add(new JLabel("Directory:"));
        bar.add(dirField);
        bar.add(browseButton);
        bar.add(runButton);
        bar.add(cancelButton);

        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 22));
        bar.add(sep);

        bar.add(saveAllButton);

        JSeparator sep2 = new JSeparator(JSeparator.VERTICAL);
        sep2.setPreferredSize(new Dimension(1, 22));
        bar.add(sep2);

        bar.add(new JLabel("Profile:"));
        bar.add(profileCombo);

        JSeparator sep3 = new JSeparator(JSeparator.VERTICAL);
        sep3.setPreferredSize(new Dimension(1, 22));
        bar.add(sep3);
        bar.add(settingsButton);

        return bar;
    }

    // ── Toolbar wiring ────────────────────────────────────────────────────

    private void wireToolbar() {
        browseButton.addActionListener(e -> browseDirectory());
        runButton.addActionListener(e -> startAnalysis());
        cancelButton.addActionListener(e -> cancelAnalysis());
        saveAllButton.addActionListener(e -> saveAllChanges());
        settingsButton.addActionListener(e -> openSettings());
        dirField.addActionListener(e -> startAnalysis());
        profileCombo.addActionListener(e -> {
            if (updatingProfileCombo) return;
            String selected = (String) profileCombo.getSelectedItem();
            if (selected != null) {
                settings.setActiveProfileName(selected);
            }
        });
    }

    private void browseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select test source directory");
        String current = dirField.getText().trim();
        if (!current.isEmpty() && Files.isDirectory(Path.of(current))) {
            chooser.setCurrentDirectory(Path.of(current).toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            dirField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void startAnalysis() {
        String dir = dirField.getText().trim();
        if (dir.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter a directory to scan.", "No Directory", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            JOptionPane.showMessageDialog(this,
                    "The specified path is not a directory:\n" + dir,
                    "Invalid Directory", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (currentService != null && !currentService.isDone()) {
            currentService.cancel(true);
        }

        settings.setLastDirectory(dir);
        model.clear();

        currentService = new AnalysisService(settings, root, model);
        currentService.execute();
        runButton.setEnabled(false);
        cancelButton.setEnabled(true);
    }

    private void cancelAnalysis() {
        if (currentService != null) {
            currentService.cancel(true);
        }
    }

    private void openSettings() {
        SettingsDialog dlg = new SettingsDialog(this, settings);
        dlg.setVisible(true);
        if (dlg.isConfirmed()) {
            refreshProfileCombo();
        }
    }

    private void refreshProfileCombo() {
        updatingProfileCombo = true;
        try {
            profileCombo.removeAllItems();
            for (AiProfile p : settings.getProfiles()) {
                profileCombo.addItem(p.getName());
            }
            profileCombo.setSelectedItem(settings.getActiveProfileName());
        } finally {
            updatingProfileCombo = false;
        }
    }

    // ── Save All Changes ──────────────────────────────────────────────────

    private void saveAllChanges() {
        List<MethodEntry> staged = model.getStagedEntries();
        if (staged.isEmpty()) return;

        // Group staged entries by source file
        Map<Path, List<MethodEntry>> byFile = new LinkedHashMap<>();
        for (MethodEntry entry : staged) {
            Path fp = entry.discovered().filePath();
            if (fp != null) byFile.computeIfAbsent(fp, k -> new ArrayList<>()).add(entry);
        }

        // Load and configure all SourcePatcher implementations
        TestDiscoveryConfig config = new TestDiscoveryConfig(
                TagEditorPanel.buildFlatSuffixes(settings),
                Set.copyOf(settings.getTestAnnotations()),
                Map.of());
        List<SourcePatcher> patchers = new ArrayList<>();
        ServiceLoader.load(SourcePatcher.class).forEach(p -> {
            p.configure(config);
            patchers.add(p);
        });

        List<String> errors = new ArrayList<>();
        Set<Path> savedFiles = new LinkedHashSet<>();
        List<AuditWriter.SavedEntry> auditEntries = new ArrayList<>();

        for (Map.Entry<Path, List<MethodEntry>> fe : byFile.entrySet()) {
            Path filePath = fe.getKey();
            List<MethodEntry> entries = fe.getValue();

            SourcePatcher patcher = patchers.stream()
                    .filter(p -> p.supports(filePath))
                    .findFirst()
                    .orElse(null);
            if (patcher == null) {
                errors.add("No patcher available for: " + filePath.getFileName());
                continue;
            }

            Map<String, List<String>> tagsToApply = new LinkedHashMap<>();
            Map<String, String> displayNames = new LinkedHashMap<>();
            for (MethodEntry e : entries) {
                tagsToApply.put(e.discovered().method(), e.getPendingTags());
                String dn = e.getPendingDisplayName();
                if (dn != null) displayNames.put(e.discovered().method(), dn);
            }

            StringWriter sw = new StringWriter();
            try {
                patcher.patch(filePath, tagsToApply, displayNames, new PrintWriter(sw));
                for (MethodEntry e : entries) {
                    // Snapshot before clearing so AuditWriter sees the applied values
                    auditEntries.add(new AuditWriter.SavedEntry(
                            e.discovered().fqcn(),
                            e.discovered().method(),
                            e.discovered().loc(),
                            e.getPendingTags(),
                            e.getPendingDisplayName(),
                            e.suggestion()));
                    e.setAppliedTags(e.getPendingTags());
                    e.clearStagedPatch();
                    model.notifyEntryChanged(e);
                }
                savedFiles.add(filePath);
            } catch (IOException ex) {
                errors.add(filePath.getFileName() + ": " + ex.getMessage());
            }
        }

        editorPanel.reloadIfAmong(savedFiles);
        saveAllButton.setEnabled(model.hasStagedChanges());

        // Write audit evidence — warn on failure, do not roll back source patches
        if (!auditEntries.isEmpty()) {
            String dir = dirField.getText().trim();
            if (!dir.isEmpty()) {
                try {
                    AuditWriter.write(Path.of(dir), auditEntries, settings.getOperatorName());
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this,
                            "Source files were saved but audit records could not be written to .methodatlas/:\n"
                                    + ex.getMessage(),
                            "Audit Write Warning", JOptionPane.WARNING_MESSAGE);
                }
            }
        }

        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Some files could not be saved:\n" + String.join("\n", errors),
                    "Save Error", JOptionPane.ERROR_MESSAGE);
        } else {
            model.setStatusMessage("Saved " + staged.size() + " method(s) across "
                    + savedFiles.size() + " file(s)");
        }
    }

    // ── Model observer ────────────────────────────────────────────────────

    private void wireModelObserver() {
        model.addPropertyChangeListener("status", evt -> {
            AnalysisModel.Status s = (AnalysisModel.Status) evt.getNewValue();
            boolean done = s == AnalysisModel.Status.DONE
                    || s == AnalysisModel.Status.ERROR
                    || s == AnalysisModel.Status.IDLE;
            runButton.setEnabled(done);
            cancelButton.setEnabled(!done);
        });
        model.addPropertyChangeListener("entries", evt -> {
            saveAllButton.setEnabled(model.hasStagedChanges());
        });
        model.addPropertyChangeListener("cleared", evt -> {
            saveAllButton.setEnabled(false);
        });
    }

    // ── Window state ──────────────────────────────────────────────────────

    private void initSplitPositions() {
        int rsp = settings.getRightSplitPosition();
        if (rsp > 0) {
            rightSplit.setDividerLocation(rsp);
        } else {
            // Default: editor gets 75% of the vertical space; tag editor the rest.
            // setDividerLocation(double) requires the pane to be realized, which
            // is guaranteed because this runs inside an invokeLater that fires
            // after setVisible(true).
            rightSplit.setDividerLocation(0.75);
        }
    }

    private void restoreWindowState() {
        setSize(settings.getWindowWidth(), settings.getWindowHeight());
        setLocationRelativeTo(null);
    }

    private void applyLastDirectory() {
        String last = settings.getLastDirectory();
        if (last != null && !last.isBlank()) {
            dirField.setText(last);
        }
    }

    private void onClose() {
        if (model.hasStagedChanges()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "You have staged changes that have not been written to disk.\n"
                            + "Save them now before closing?",
                    "Unsaved Staged Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                return;
            }
            if (choice == JOptionPane.YES_OPTION) {
                saveAllChanges();
            }
        }
        if (currentService != null && !currentService.isDone()) {
            currentService.cancel(true);
        }
        settings.setWindowWidth(getWidth());
        settings.setWindowHeight(getHeight());
        SettingsManager.save(settings);
        dispose();
        System.exit(0);
    }
}
