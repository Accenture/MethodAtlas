package org.egothor.methodatlas.gui.panel;

import org.egothor.methodatlas.gui.model.AnalysisModel;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.beans.PropertyChangeEvent;

/**
 * Slim status bar displayed at the bottom of the main window.
 *
 * <p>Shows the current analysis status message on the left, an indeterminate
 * progress bar in the centre (visible only during active work), and a
 * method-count summary on the right.</p>
 */
public final class StatusBar extends JPanel {

    private final JLabel messageLabel = new JLabel("Ready");
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel countLabel = new JLabel();

    /** @param model model to observe */
    public StatusBar(AnalysisModel model) {
        setLayout(new BorderLayout(8, 0));
        setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                new EmptyBorder(4, 8, 4, 8)));

        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(160, 14));
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
            }
            case "statusMessage" -> messageLabel.setText((String) evt.getNewValue());
            case "entries" -> {
                // Eagerly update via the source model; count is cheap
                AnalysisModel src = (AnalysisModel) evt.getSource();
                countLabel.setText(src.getTotalMethodCount() + " methods | "
                        + src.getClassCount() + " classes");
            }
            case "cleared" -> countLabel.setText("");
            default -> { /* ignore */ }
        }
    }
}
