package org.egothor.methodatlas.gui.panel;

import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.gui.model.AnalysisModel;
import org.egothor.methodatlas.gui.model.AppSettings;
import org.egothor.methodatlas.gui.model.MethodEntry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bottom-right panel for reviewing and staging tag suggestions for a method.
 *
 * <p>Shows the currently selected method's existing {@code @Tag} values
 * alongside the AI-suggested tags as interactive toggle chips.  The user
 * can accept or reject individual AI suggestions, enter manual overrides,
 * and <em>stage</em> the result via <strong>Apply to Source</strong>.
 * Staged changes are held in memory and written to disk together when the
 * <strong>Save All Changes</strong> toolbar button is pressed, ensuring that
 * all modifications to a file are applied in a single pass (preventing line
 * number drift when multiple methods in the same class are patched).</p>
 */
public final class TagEditorPanel extends JPanel {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    // ── UI components ─────────────────────────────────────────────────────

    private final JLabel methodLabel = new JLabel("No method selected");
    private final JPanel currentTagsRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    private final JPanel aiTagsRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
    private final JLabel reasonLabel = new JLabel();
    private final JLabel stagedLabel = new JLabel();
    private final JTextField overrideField = new JTextField();
    private final JButton applyButton = new JButton("Stage Selection");
    private final JButton applyAiButton = new JButton("Accept All AI Tags");
    private final JButton unstageButton = new JButton("Unstage");

    // ── State ─────────────────────────────────────────────────────────────

    private final AnalysisModel model;
    private final AppSettings settings;
    private MethodEntry currentEntry;

    /**
     * @param model    model to observe
     * @param settings settings (file suffixes and test annotations)
     */
    public TagEditorPanel(AnalysisModel model, AppSettings settings) {
        super(new BorderLayout(0, 6));
        this.model = model;
        this.settings = settings;

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

        // ── Staged indicator ──────────────────────────────────────────────
        stagedLabel.setFont(stagedLabel.getFont().deriveFont(Font.BOLD, 11f));
        stagedLabel.setForeground(new Color(0xE65100));
        stagedLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
        stagedLabel.setVisible(false);

        // ── Override ──────────────────────────────────────────────────────
        JPanel overridePanel = new JPanel(new BorderLayout(4, 0));
        overridePanel.add(new JLabel("Custom override (comma-separated): "), BorderLayout.WEST);
        overridePanel.add(overrideField, BorderLayout.CENTER);

        // ── Actions ───────────────────────────────────────────────────────
        applyButton.setToolTipText(
                "Stage the currently toggled AI chips plus any custom override — use Accept All AI Tags to skip toggling");
        applyAiButton.setToolTipText("Stage all AI-suggested tags for this method");
        unstageButton.setToolTipText("Discard staged changes for this method without writing to disk");
        applyButton.setEnabled(false);
        applyAiButton.setEnabled(false);
        unstageButton.setEnabled(false);

        applyButton.addActionListener(e -> stageSelectedTags());
        applyAiButton.addActionListener(e -> stageAllAiTags());
        unstageButton.addActionListener(e -> unstageCurrentEntry());

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonRow.add(unstageButton);
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

        JPanel south = new JPanel(new BorderLayout());
        south.add(stagedLabel, BorderLayout.NORTH);
        south.add(buttonRow, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
    }

    // ── Event handling ────────────────────────────────────────────────────

    private void onSelectionChanged(PropertyChangeEvent evt) {
        currentEntry = (MethodEntry) evt.getNewValue();
        refreshUi();
    }

    private void onEntriesChanged(PropertyChangeEvent evt) {
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

        // Current tags area: show pending tags (orange) if staged, else source tags (blue)
        currentTagsRow.removeAll();
        if (currentEntry.hasPendingChanges()) {
            List<String> pending = currentEntry.getPendingTags();
            if (pending.isEmpty()) {
                currentTagsRow.add(new JLabel("<html><i color='gray'>none (staged)</i></html>"));
            } else {
                for (String tag : pending) {
                    currentTagsRow.add(buildStaticChip(tag, new Color(0xE65100)));
                }
            }
            stagedLabel.setText("⏳ Staged — press Save All Changes in the toolbar to write to disk");
            stagedLabel.setVisible(true);
        } else {
            List<String> sourceTags = currentEntry.discovered().tags();
            if (sourceTags.isEmpty()) {
                currentTagsRow.add(new JLabel("<html><i color='gray'>none</i></html>"));
            } else {
                for (String tag : sourceTags) {
                    currentTagsRow.add(buildStaticChip(tag, new Color(0x1565C0)));
                }
            }
            stagedLabel.setVisible(false);
        }

        // AI suggestion chips (toggleable)
        aiTagsRow.removeAll();
        List<String> sourceTags = currentEntry.discovered().tags();
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

        boolean canApply = currentEntry.discovered().filePath() != null;
        applyButton.setEnabled(canApply);
        applyAiButton.setEnabled(hasAi && canApply);
        unstageButton.setEnabled(currentEntry.hasPendingChanges());

        revalidate();
        repaint();
    }

    private void clearUi() {
        currentEntry = null;
        methodLabel.setText("No method selected");
        currentTagsRow.removeAll();
        aiTagsRow.removeAll();
        reasonLabel.setText("");
        stagedLabel.setVisible(false);
        overrideField.setText("");
        applyButton.setEnabled(false);
        applyAiButton.setEnabled(false);
        unstageButton.setEnabled(false);
        revalidate();
        repaint();
    }

    // ── Staging ───────────────────────────────────────────────────────────

    private void stageSelectedTags() {
        if (currentEntry == null) return;

        Set<String> tags = new LinkedHashSet<>(currentEntry.discovered().tags());
        for (Component c : aiTagsRow.getComponents()) {
            if (c instanceof JToggleButton btn && btn.isSelected()) {
                tags.add(btn.getText());
            }
        }
        String override = overrideField.getText().trim();
        if (!override.isEmpty()) {
            for (String t : override.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) tags.add(trimmed);
            }
        }

        String displayName = resolveDisplayName(currentEntry);
        stageEntry(currentEntry, new ArrayList<>(tags), displayName);
    }

    private void stageAllAiTags() {
        if (currentEntry == null || currentEntry.suggestion() == null) return;
        Set<String> tags = new LinkedHashSet<>(currentEntry.discovered().tags());
        List<String> aiTags = currentEntry.suggestion().tags();
        if (aiTags != null) tags.addAll(aiTags);
        String displayName = resolveDisplayName(currentEntry);
        stageEntry(currentEntry, new ArrayList<>(tags), displayName);
    }

    private void unstageCurrentEntry() {
        if (currentEntry == null || !currentEntry.hasPendingChanges()) return;
        currentEntry.clearStagedPatch();
        model.notifyEntryChanged(currentEntry);
        model.setStatusMessage("Staged changes cleared for " + currentEntry.discovered().method());
        refreshUi();
    }

    private void stageEntry(MethodEntry entry, List<String> tags, String displayName) {
        entry.setStagedPatch(tags, displayName);
        model.notifyEntryChanged(entry);
        overrideField.setText("");
        model.setStatusMessage(
                "Staged " + entry.discovered().method() + " — press Save All Changes to write to disk");
        refreshUi();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Returns the AI-suggested display name to stage alongside the tags, or
     * {@code null} when one is not available or already set in source.
     */
    private static String resolveDisplayName(MethodEntry entry) {
        String dn = entry.suggestedDisplayName();
        if (dn != null && !dn.isBlank() && entry.discovered().displayName() == null) {
            return dn;
        }
        return null;
    }

    /**
     * Returns the flat suffix list required by {@link TestDiscoveryConfig}
     * from the per-plugin suffix map in {@code settings}.
     */
    public static List<String> buildFlatSuffixes(AppSettings settings) {
        List<String> result = new ArrayList<>();
        settings.getPluginSuffixes().forEach((pluginId, masks) ->
                masks.forEach(m -> result.add(pluginId + ":" + m)));
        return result;
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
        updateColor.actionPerformed(null);
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
