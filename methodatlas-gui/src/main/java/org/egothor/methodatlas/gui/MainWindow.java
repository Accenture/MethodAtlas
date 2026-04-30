package org.egothor.methodatlas.gui;

import org.egothor.methodatlas.gui.dialog.SettingsDialog;
import org.egothor.methodatlas.gui.model.AnalysisModel;
import org.egothor.methodatlas.gui.model.AppSettings;
import org.egothor.methodatlas.gui.panel.ActivityPanel;
import org.egothor.methodatlas.gui.panel.EditorPanel;
import org.egothor.methodatlas.gui.panel.ScanPanel;
import org.egothor.methodatlas.gui.panel.StatusBar;
import org.egothor.methodatlas.gui.panel.TagEditorPanel;
import org.egothor.methodatlas.gui.service.AnalysisService;
import org.egothor.methodatlas.gui.service.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Main application window for MethodAtlas GUI.
 *
 * <p>Layout overview:</p>
 * <pre>
 * ┌──────────────────────────────────────────────────────────┐
 * │  Toolbar: directory chooser · Run · Cancel · Settings    │
 * ├──────────────┬───────────────────────────────────────────┤
 * │              │  Source editor (RSyntaxTextArea)           │
 * │  Results     ├───────────────────────────────────────────┤
 * │  tree        │  Tag editor (AI chips + override + apply)  │
 * ├──────────────┴───────────────────────────────────────────┤
 * │  Activity panel (hidden when idle, collapsible log)      │
 * ├──────────────────────────────────────────────────────────┤
 * │  Status bar                                              │
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class MainWindow extends JFrame {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    // ── State ─────────────────────────────────────────────────────────────

    private final AppSettings settings;
    private final AnalysisModel model = new AnalysisModel();
    private AnalysisService currentService;

    // ── Toolbar controls ──────────────────────────────────────────────────

    private final JTextField dirField = new JTextField(32);
    private final JButton browseButton = new JButton("Browse…");
    private final JButton runButton = new JButton("▶  Run Analysis");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton settingsButton = new JButton("⚙  Settings");

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
        tagEditorPanel = new TagEditorPanel(model, settings, editorPanel);
        activityPanel = new ActivityPanel(model);
        statusBar = new StatusBar(model);

        buildLayout();
        wireToolbar();
        wireModelObserver();
        restoreWindowState();
        applyLastDirectory();
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private void buildLayout() {
        // Right pane: editor on top (80%), tag editor on bottom (20%)
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPanel, tagEditorPanel);
        rightSplit.setResizeWeight(0.8);
        rightSplit.setBorder(null);
        int rsp = settings.getRightSplitPosition();
        if (rsp > 0) rightSplit.setDividerLocation(rsp);

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

        bar.add(new JLabel("Directory:"));
        bar.add(dirField);
        bar.add(browseButton);
        bar.add(runButton);
        bar.add(cancelButton);

        // Separator
        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 22));
        bar.add(sep);
        bar.add(settingsButton);

        return bar;
    }

    // ── Toolbar wiring ────────────────────────────────────────────────────

    private void wireToolbar() {
        browseButton.addActionListener(e -> browseDirectory());
        runButton.addActionListener(e -> startAnalysis());
        cancelButton.addActionListener(e -> cancelAnalysis());
        settingsButton.addActionListener(e -> openSettings());
        dirField.addActionListener(e -> startAnalysis());
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
    }

    // ── Window state ──────────────────────────────────────────────────────

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
