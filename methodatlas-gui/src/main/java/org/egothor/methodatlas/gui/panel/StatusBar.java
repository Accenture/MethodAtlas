package org.egothor.methodatlas.gui.panel;

import org.egothor.methodatlas.gui.model.AnalysisModel;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.beans.PropertyChangeEvent;

/**
 * Slim single-row panel displayed at the very bottom of the main window.
 *
 * <p>The bar has three zones:</p>
 * <ul>
 *   <li><strong>Left</strong> — human-readable status message reflecting the
 *       phase and the class currently being processed (for example
 *       {@code "AI enrichment [3/42] — FooTest"})</li>
 *   <li><strong>Centre</strong> — progress bar: indeterminate during the
 *       file-system scan; determinate (with a {@code "X / Y"} string
 *       overlay) during the AI enrichment phase; hidden when idle</li>
 *   <li><strong>Right</strong> — running totals of discovered methods and
 *       classes, for example {@code "42 methods | 7 classes"}</li>
 * </ul>
 *
 * <p>This component registers itself as a {@link java.beans.PropertyChangeListener}
 * on the supplied {@link AnalysisModel} and must therefore be created on the
 * Swing Event Dispatch Thread.</p>
 *
 * @see ActivityPanel
 */
public final class StatusBar extends JPanel {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final JLabel messageLabel = new JLabel("Ready");
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel countLabel = new JLabel();

    /**
     * Constructs the status bar and registers it as a property-change
     * listener on {@code model}.
     *
     * <p>Must be called on the Swing Event Dispatch Thread.</p>
     *
     * @param model model whose status, progress, entry-count, and clear
     *              events drive this panel's display; must not be
     *              {@code null}
     */
    public StatusBar(AnalysisModel model) {
        super();
        setLayout(new BorderLayout(8, 0));
        setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                new EmptyBorder(4, 8, 4, 8)));

        progressBar.setStringPainted(false);
        progressBar.setPreferredSize(new Dimension(180, 14));
        progressBar.setVisible(false);

        countLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        countLabel.setFont(countLabel.getFont().deriveFont(Font.PLAIN, 11f));

        add(messageLabel, BorderLayout.WEST);
        add(progressBar, BorderLayout.CENTER);
        add(countLabel, BorderLayout.EAST);

        model.addPropertyChangeListener(this::onModelChange);
    }

    private void onModelChange(PropertyChangeEvent evt) {
        switch (evt.getPropertyName()) {
            case "status" -> {
                AnalysisModel.Status s = (AnalysisModel.Status) evt.getNewValue();
                boolean busy = s == AnalysisModel.Status.SCANNING
                        || s == AnalysisModel.Status.AI_RUNNING;
                progressBar.setVisible(busy);
                if (s == AnalysisModel.Status.SCANNING) {
                    progressBar.setIndeterminate(true);
                    progressBar.setStringPainted(false);
                } else if (s == AnalysisModel.Status.AI_RUNNING) {
                    progressBar.setIndeterminate(false);
                    progressBar.setStringPainted(true);
                } else {
                    progressBar.setIndeterminate(false);
                    progressBar.setStringPainted(false);
                }
            }
            case "statusMessage" -> messageLabel.setText((String) evt.getNewValue());
            case "progress" -> {
                AnalysisModel src = (AnalysisModel) evt.getSource();
                int current = src.getProgressCurrent();
                int total = src.getProgressTotal();
                if (total > 0) {
                    progressBar.setMaximum(total);
                    progressBar.setValue(current);
                    progressBar.setString(current + " / " + total);
                }
            }
            case "entries" -> {
                AnalysisModel src = (AnalysisModel) evt.getSource();
                countLabel.setText(src.getTotalMethodCount() + " methods | "
                        + src.getClassCount() + " classes");
            }
            case "cleared" -> {
                countLabel.setText("");
                progressBar.setValue(0);
                progressBar.setString(null);
            }
            default -> { /* ignore */ }
        }
    }
}
