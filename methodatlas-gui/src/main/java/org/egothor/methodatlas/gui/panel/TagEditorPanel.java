package org.egothor.methodatlas.gui.panel;

import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.gui.model.AnalysisModel;
import org.egothor.methodatlas.gui.model.AppSettings;
import org.egothor.methodatlas.gui.model.MethodEntry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bottom-right panel for reviewing and applying tag suggestions to a method.
 *
 * <p>Shows the currently selected method's existing {@code @Tag} values
 * alongside the AI-suggested tags as interactive toggle chips.  The user
 * can accept or reject individual AI suggestions, enter manual overrides,
 * and apply the result directly to the source file via the registered
 * {@link SourcePatcher} implementations.</p>
 */
public final class TagEditorPanel extends JPanel {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = Logger.getLogger(TagEditorPanel.class.getName());

    // ── UI components ─────────────────────────────────────────────────────

    private final JLabel methodLabel = new JLabel("No method selected");
    private final JPanel currentTagsRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    private final JPanel aiTagsRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    private final JLabel reasonLabel = new JLabel();
    private final JTextField overrideField = new JTextField();
    private final JButton applyButton = new JButton("Apply to Source");
    private final JButton applyAiButton = new JButton("Accept All AI Tags");

    // ── State ─────────────────────────────────────────────────────────────

    private final AnalysisModel model;
    private final AppSettings settings;
    private final EditorPanel editorPanel;
    private MethodEntry currentEntry;

    /**
     * @param model       model to observe
     * @param settings    settings (file suffixes and test annotations)
     * @param editorPanel editor to refresh after applying a patch
     */
    public TagEditorPanel(AnalysisModel model, AppSettings settings, EditorPanel editorPanel) {
        super(new BorderLayout(0, 6));
        this.model = model;
        this.settings = settings;
        this.editorPanel = editorPanel;

        setBorder(new EmptyBorder(8, 8, 8, 8));
        buildUi();

        model.addPropertyChangeListener("selectedEntry", this::onSelectionChanged);
        model.addPropertyChangeListener("entries", this::onEntriesChanged);
        model.addPropertyChangeListener("cleared", e -> clearUi());
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private void buildUi() {
        // ── Header ────────────────────────────────────────────────────────
        methodLabel.setFont(methodLabel.getFont().deriveFont(Font.BOLD, 12f));
        methodLabel.setBorder(new EmptyBorder(0, 0, 4, 0));

        // ── Current tags ──────────────────────────────────────────────────
        JPanel currentPanel = new JPanel(new BorderLayout());
        currentPanel.add(new JLabel("Current tags in source:"), BorderLayout.NORTH);
        currentTagsRow.setOpaque(false);
        currentPanel.add(currentTagsRow, BorderLayout.CENTER);

        // ── AI suggestions ────────────────────────────────────────────────
        JPanel aiPanel = new JPanel(new BorderLayout(0, 2));
        aiPanel.add(new JLabel("AI suggested tags (click to toggle):"), BorderLayout.NORTH);
        aiTagsRow.setOpaque(false);
        aiPanel.add(aiTagsRow, BorderLayout.CENTER);

        reasonLabel.setFont(reasonLabel.getFont().deriveFont(Font.ITALIC, 11f));
        reasonLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        reasonLabel.setBorder(new EmptyBorder(2, 0, 0, 0));
        aiPanel.add(reasonLabel, BorderLayout.SOUTH);

        // ── Override ──────────────────────────────────────────────────────
        JPanel overridePanel = new JPanel(new BorderLayout(4, 0));
        overridePanel.add(new JLabel("Custom override (comma-separated): "), BorderLayout.WEST);
        overridePanel.add(overrideField, BorderLayout.CENTER);

        // ── Actions ───────────────────────────────────────────────────────
        applyButton.setToolTipText("Apply the toggled AI tags (plus any override) to the source file");
        applyAiButton.setToolTipText("Accept all AI-suggested tags for this method");
        applyButton.setEnabled(false);
        applyAiButton.setEnabled(false);

        applyButton.addActionListener(e -> applySelectedTags());
        applyAiButton.addActionListener(e -> acceptAllAiTags());

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonRow.add(applyAiButton);
        buttonRow.add(applyButton);

        // ── Sections ──────────────────────────────────────────────────────
        JPanel tagsSection = new JPanel(new GridLayout(2, 1, 0, 4));
        tagsSection.add(currentPanel);
        tagsSection.add(aiPanel);

        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.add(tagsSection, BorderLayout.CENTER);
        center.add(overridePanel, BorderLayout.SOUTH);

        add(methodLabel, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(buttonRow, BorderLayout.SOUTH);
    }

    // ── Event handling ────────────────────────────────────────────────────

    private void onSelectionChanged(PropertyChangeEvent evt) {
        currentEntry = (MethodEntry) evt.getNewValue();
        refreshUi();
    }

    private void onEntriesChanged(PropertyChangeEvent evt) {
        // Refresh if the currently displayed entry was updated by AI
        if (currentEntry != null && evt.getNewValue() instanceof MethodEntry updated
                && updated.toString().equals(currentEntry.toString())) {
            currentEntry = updated;
            refreshUi();
        }
    }

    // ── UI refresh ────────────────────────────────────────────────────────

    private void refreshUi() {
        if (currentEntry == null) {
            clearUi();
            return;
        }

        methodLabel.setText(currentEntry.discovered().fqcn()
                + " # " + currentEntry.discovered().method());

        // Current tags chips (display only)
        currentTagsRow.removeAll();
        List<String> sourceTags = currentEntry.discovered().tags();
        if (sourceTags.isEmpty()) {
            currentTagsRow.add(new JLabel("<html><i color='gray'>none</i></html>"));
        } else {
            for (String tag : sourceTags) {
                currentTagsRow.add(buildStaticChip(tag, new Color(0x1565C0)));
            }
        }

        // AI suggestion chips (toggleable)
        aiTagsRow.removeAll();
        boolean hasAi = currentEntry.suggestion() != null && currentEntry.suggestion().securityRelevant();
        if (!hasAi) {
            String msg = currentEntry.suggestion() == null ? "No AI data yet"
                    : "Not classified as security-relevant by AI";
            aiTagsRow.add(new JLabel("<html><i color='gray'>" + msg + "</i></html>"));
            reasonLabel.setText("");
        } else {
            List<String> aiTags = currentEntry.suggestion().tags();
            if (aiTags != null) {
                for (String tag : aiTags) {
                    boolean alreadyPresent = sourceTags.contains(tag);
                    aiTagsRow.add(buildToggleChip(tag, alreadyPresent));
                }
            }
            String reason = currentEntry.suggestion().reason();
            reasonLabel.setText(reason != null && !reason.isBlank()
                    ? "<html><i>" + escHtml(truncate(reason, 120)) + "</i></html>" : "");
        }

        applyButton.setEnabled(currentEntry.discovered().filePath() != null);
        applyAiButton.setEnabled(hasAi && currentEntry.discovered().filePath() != null);

        revalidate();
        repaint();
    }

    private void clearUi() {
        currentEntry = null;
        methodLabel.setText("No method selected");
        currentTagsRow.removeAll();
        aiTagsRow.removeAll();
        reasonLabel.setText("");
        overrideField.setText("");
        applyButton.setEnabled(false);
        applyAiButton.setEnabled(false);
        revalidate();
        repaint();
    }

    // ── Tag application ───────────────────────────────────────────────────

    private void applySelectedTags() {
        if (currentEntry == null) return;

        Set<String> tags = new LinkedHashSet<>(currentEntry.discovered().tags());
        // Add selected AI tags
        for (Component c : aiTagsRow.getComponents()) {
            if (c instanceof JToggleButton btn && btn.isSelected()) {
                tags.add(btn.getText());
            }
        }
        // Add overrides
        String override = overrideField.getText().trim();
        if (!override.isEmpty()) {
            for (String t : override.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) tags.add(trimmed);
            }
        }

        patch(currentEntry, new ArrayList<>(tags));
    }

    private void acceptAllAiTags() {
        if (currentEntry == null || currentEntry.suggestion() == null) return;
        Set<String> tags = new LinkedHashSet<>(currentEntry.discovered().tags());
        List<String> aiTags = currentEntry.suggestion().tags();
        if (aiTags != null) tags.addAll(aiTags);
        patch(currentEntry, new ArrayList<>(tags));
    }

    private void patch(MethodEntry entry, List<String> tags) {
        TestDiscoveryConfig discoveryConfig = new TestDiscoveryConfig(
                settings.getFileSuffixes(),
                Set.copyOf(settings.getTestAnnotations()),
                Map.of());

        List<SourcePatcher> patchers = new ArrayList<>();
        ServiceLoader.load(SourcePatcher.class).forEach(p -> {
            p.configure(discoveryConfig);
            patchers.add(p);
        });

        SourcePatcher patcher = patchers.stream()
                .filter(p -> p.supports(entry.discovered().filePath()))
                .findFirst().orElse(null);

        if (patcher == null) {
            JOptionPane.showMessageDialog(this,
                    "No SourcePatcher supports this file type.",
                    "Patch Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        StringWriter logBuffer = new StringWriter();
        try (PrintWriter log = new PrintWriter(logBuffer)) {
            Map<String, List<String>> tagsToApply = Map.of(entry.discovered().method(), tags);
            Map<String, String> displayNames = Map.of();
            String displayName = entry.suggestedDisplayName();
            if (displayName != null && !displayName.isBlank()
                    && entry.discovered().displayName() == null) {
                displayNames = Map.of(entry.discovered().method(), displayName);
            }
            int changes = patcher.patch(entry.discovered().filePath(), tagsToApply, displayNames, log);
            entry.setAppliedTags(tags);
            model.setStatusMessage("Patched " + changes + " annotation(s) in "
                    + entry.discovered().filePath().getFileName());
            overrideField.setText("");
            editorPanel.reloadCurrentFile(entry.discovered().beginLine());
            refreshUi();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Patch failed", e);
            JOptionPane.showMessageDialog(this,
                    "Patch failed:\n" + e.getMessage(),
                    "Patch Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Chip factories ────────────────────────────────────────────────────

    private static JLabel buildStaticChip(String text, Color bg) {
        JLabel chip = new JLabel(text);
        chip.setOpaque(true);
        chip.setBackground(bg);
        chip.setForeground(Color.WHITE);
        chip.setFont(chip.getFont().deriveFont(Font.PLAIN, 11f));
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bg.darker(), 1, true),
                new EmptyBorder(2, 7, 2, 7)));
        chip.putClientProperty("JComponent.roundRect", Boolean.TRUE);
        return chip;
    }

    private JToggleButton buildToggleChip(String tag, boolean initiallySelected) {
        JToggleButton btn = new JToggleButton(tag, initiallySelected);
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 11f));
        btn.setFocusPainted(false);
        btn.putClientProperty("JButton.buttonType", "roundRect");
        btn.setMargin(new Insets(2, 7, 2, 7));

        ActionListener updateColor = e -> {
            Color bg = btn.isSelected()
                    ? new Color(0x2E7D32) : UIManager.getColor("Button.background");
            Color fg = btn.isSelected() ? Color.WHITE : UIManager.getColor("Button.foreground");
            btn.setBackground(bg);
            btn.setForeground(fg);
        };
        btn.addActionListener(updateColor);
        updateColor.actionPerformed(null); // set initial colour
        return btn;
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen - 1) + "…" : s;
    }

    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * A {@link FlowLayout} variant that wraps to the next line automatically
     * so that tag chips reflow when the panel is resized.
     */
    private static final class WrapLayout extends FlowLayout {

        @java.io.Serial
        private static final long serialVersionUID = 1L;
        WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            return layoutSize(target, false);
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getSize().width;
                if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;
                Insets insets = target.getInsets();
                int maxWidth = targetWidth - insets.left - insets.right - getHgap() * 2;

                int width = 0;
                int height = 0;
                int rowWidth = 0;
                int rowHeight = 0;
                int nmembers = target.getComponentCount();

                for (int i = 0; i < nmembers; i++) {
                    Component m = target.getComponent(i);
                    if (!m.isVisible()) continue;
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                        width = Math.max(width, rowWidth);
                        height += rowHeight + getVgap();
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    rowWidth += d.width + getHgap();
                    rowHeight = Math.max(rowHeight, d.height);
                }
                width = Math.max(width, rowWidth);
                height += rowHeight + getVgap() + insets.top + insets.bottom;
                return new Dimension(width, height);
            }
        }
    }
}
