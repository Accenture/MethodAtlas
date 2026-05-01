package org.egothor.methodatlas.gui.panel;

import org.egothor.methodatlas.gui.model.AnalysisModel;
import org.egothor.methodatlas.gui.model.MethodEntry;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Source-code editor panel with syntax highlighting and line numbers.
 *
 * <p>Uses RSyntaxTextArea (BSD 3-Clause) displayed inside an
 * {@code RTextScrollPane} which adds the line-number gutter.  The editor
 * is read-only; patching is performed by the {@link TagEditorPanel}.</p>
 *
 * <p>When the user selects a method in the results tree the panel loads the
 * corresponding source file and centers the viewport on the method's first
 * line so that context lines above and below remain visible.</p>
 */
public final class EditorPanel extends JPanel {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = Logger.getLogger(EditorPanel.class.getName());

    private final RSyntaxTextArea textArea;
    private final JLabel fileLabel = new JLabel(" ");
    private Path currentFile;

    /** @param model model to observe for selection changes */
    public EditorPanel(AnalysisModel model) {
        super(new BorderLayout());

        textArea = buildTextArea();
        RTextScrollPane scrollPane = new RTextScrollPane(textArea);
        scrollPane.setLineNumbersEnabled(true);
        scrollPane.setFoldIndicatorEnabled(true);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        // Header bar showing the current file path
        fileLabel.setFont(fileLabel.getFont().deriveFont(Font.PLAIN, 11f));
        fileLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        fileLabel.setBorder(new EmptyBorder(3, 8, 3, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.add(fileLabel, BorderLayout.WEST);
        header.setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, UIManager.getColor("Separator.foreground")));

        add(header, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        model.addPropertyChangeListener("selectedEntry", this::onSelectionChanged);
        model.addPropertyChangeListener("cleared", e -> clearEditor());
    }

    // ── Event handlers ────────────────────────────────────────────────────

    private void onSelectionChanged(PropertyChangeEvent evt) {
        MethodEntry entry = (MethodEntry) evt.getNewValue();
        if (entry == null) return;

        Path filePath = entry.discovered().filePath();
        if (filePath == null) {
            showInlineSource(entry);
            return;
        }
        loadFile(filePath, entry.discovered().beginLine());
    }

    private void showInlineSource(MethodEntry entry) {
        String src = entry.discovered().sourceContent().get().orElse(null);
        if (src == null) {
            clearEditor();
            return;
        }
        setSource(src, inferSyntaxStyle(null), null, entry.discovered().beginLine());
    }

    private void loadFile(Path file, int targetLine) {
        if (file.equals(currentFile)) {
            scrollToLine(targetLine);
            return;
        }
        try {
            String content = Files.readString(file);
            setSource(content, inferSyntaxStyle(file), file, targetLine);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read source file: " + file, e);
            fileLabel.setText("Cannot read: " + file.getFileName());
        }
    }

    private void setSource(String content, String syntaxStyle, Path file, int targetLine) {
        currentFile = file;
        textArea.setSyntaxEditingStyle(syntaxStyle);
        textArea.setText(content);
        textArea.setCaretPosition(0);

        String labelText = file != null ? file.toAbsolutePath().toString() : "(inline source)";
        fileLabel.setText(labelText);

        scrollToLine(targetLine);
    }

    private void scrollToLine(int line) {
        if (line <= 0) return;
        try {
            int offset = textArea.getLineStartOffset(line - 1);
            textArea.setCaretPosition(offset);
            // Defer centering until after the viewport has settled its layout
            SwingUtilities.invokeLater(() -> centerViewOnOffset(offset));
        } catch (Exception e) {
            // Silently ignore out-of-range lines
        }
    }

    private void centerViewOnOffset(int offset) {
        try {
            Rectangle2D r = textArea.modelToView2D(offset);
            if (r == null) return;
            Container parent = textArea.getParent();
            if (!(parent instanceof JViewport vp)) return;
            Dimension extent = vp.getExtentSize();
            int newY = Math.max(0, (int) r.getY() - extent.height / 2 + (int) r.getHeight() / 2);
            vp.setViewPosition(new Point(vp.getViewPosition().x, newY));
        } catch (Exception ex) {
            // Ignore — viewport not yet realized or offset out of range
        }
    }

    private void clearEditor() {
        currentFile = null;
        textArea.setText("");
        fileLabel.setText(" ");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static RSyntaxTextArea buildTextArea() {
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setEditable(false);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setHighlightCurrentLine(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setTabSize(4);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        return area;
    }

    private static String inferSyntaxStyle(Path file) {
        if (file == null) return SyntaxConstants.SYNTAX_STYLE_JAVA;
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".cs")) return SyntaxConstants.SYNTAX_STYLE_CSHARP;
        if (name.endsWith(".ts") || name.endsWith(".tsx")) return SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT;
        if (name.endsWith(".js") || name.endsWith(".jsx")) return SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
        return SyntaxConstants.SYNTAX_STYLE_JAVA;
    }

    /**
     * Reloads the currently displayed file from disk, preserving the current
     * viewport scroll position.
     *
     * <p>Called after "Save All Changes" so the user sees the freshly written
     * annotations without losing their place in the file.</p>
     */
    public void reloadCurrentFilePreservingScroll() {
        if (currentFile == null) return;
        Container parent = textArea.getParent();
        Point savedPos = parent instanceof JViewport vp ? vp.getViewPosition() : new Point(0, 0);
        try {
            String content = Files.readString(currentFile);
            textArea.setSyntaxEditingStyle(inferSyntaxStyle(currentFile));
            textArea.setText(content);
            SwingUtilities.invokeLater(() -> {
                Container p = textArea.getParent();
                if (p instanceof JViewport v) {
                    v.setViewPosition(savedPos);
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot reload source file: " + currentFile, e);
        }
    }

    /**
     * Reloads the currently displayed file if it is one of the given paths.
     *
     * @param savedPaths set of file paths that were just written to disk
     */
    public void reloadIfAmong(java.util.Collection<Path> savedPaths) {
        if (currentFile != null && savedPaths.contains(currentFile)) {
            reloadCurrentFilePreservingScroll();
        }
    }
}
