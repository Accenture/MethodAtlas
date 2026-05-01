package org.egothor.methodatlas.gui.panel;

import org.egothor.methodatlas.gui.model.AnalysisModel;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeEvent;
import java.time.Instant;

/**
 * Collapsible panel that provides real-time visibility into the analysis
 * progress, placed above the {@link StatusBar} in the main window.
 *
 * <h2>Visible regions</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ ▶ Activity │ → FooTest           │  3 / 42  │  00:01:23     │
 * ├──────────────────────────────────────────────────────────────┤
 * │  ✓  BarTest                          0.8 s,  3 method(s)    │
 * │  ✓  BazTest                          1.2 s,  5 method(s)    │
 * │  ✗  QuxTest — AI error               3.0 s,  2 method(s)    │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>The header row is always visible when analysis is running and shows:
 * <ul>
 *   <li>a toggle button ({@code ▶} / {@code ▼}) that expands or collapses
 *       the log area below it</li>
 *   <li>the simple class name currently being processed by the AI engine,
 *       prefixed with {@code →}</li>
 *   <li>a progress counter ({@code current / total}) during the AI phase</li>
 *   <li>elapsed wall-clock time since the analysis started, updated every
 *       second by a {@link Timer}</li>
 * </ul>
 * <p>The log area (collapsed by default) appends one line per completed class,
 * showing a success ({@code ✓}) or error ({@code ✗}) indicator, the class
 * name, the AI call duration, and the method count.  The area auto-scrolls to
 * keep the most recent entry visible.</p>
 *
 * <h2>Visibility lifecycle</h2>
 * <p>The panel hides itself when the model is {@link AnalysisModel.Status#IDLE},
 * and shows itself as soon as a
 * {@link AnalysisModel.Status#SCANNING} or
 * {@link AnalysisModel.Status#AI_RUNNING} status is received.  It remains
 * visible after the analysis reaches {@link AnalysisModel.Status#DONE} or
 * {@link AnalysisModel.Status#ERROR} so the user can review the log, and
 * hides again only when a new run calls {@link AnalysisModel#clear()}.</p>
 *
 * <h2>Thread safety</h2>
 * <p>This component registers itself as a
 * {@link java.beans.PropertyChangeListener} on the supplied
 * {@link AnalysisModel} and must therefore be created and used exclusively
 * on the Swing Event Dispatch Thread.</p>
 *
 * @see StatusBar
 * @see AnalysisModel
 */
public final class ActivityPanel extends JPanel {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Number of visible text rows in the collapsed log area. */
    private static final int LOG_ROWS = 4;

    /** Monospaced font used in the log area for column-aligned output. */
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 11);

    // ── Header controls ───────────────────────────────────────────────────

    private final JButton toggleButton = new JButton("▶ Activity");
    private final JLabel currentLabel  = new JLabel();
    private final JLabel progressLabel = new JLabel();
    private final JLabel elapsedLabel  = new JLabel();

    // ── Log area ──────────────────────────────────────────────────────────

    private final JTextArea logArea = new JTextArea(LOG_ROWS, 0);
    private final JScrollPane logScroll;

    // ── State ─────────────────────────────────────────────────────────────

    /** {@code true} when the log area scroll pane is visible. */
    private boolean logExpanded;

    /** Wall-clock instant at which the most recent analysis run started. */
    private Instant analysisStart;

    /** Fires every second to refresh the elapsed-time label. */
    private final Timer elapsedTimer;

    /**
     * Constructs the activity panel and registers it as a property-change
     * listener on {@code model}.
     *
     * <p>The panel is initially invisible; it becomes visible automatically
     * when the model transitions to {@link AnalysisModel.Status#SCANNING}
     * or {@link AnalysisModel.Status#AI_RUNNING}.  Must be called on the
     * Swing Event Dispatch Thread.</p>
     *
     * @param model model whose status, progress, current-class, and
     *              class-completion events drive this panel; must not be
     *              {@code null}
     */
    public ActivityPanel(AnalysisModel model) {
        super();
        setLayout(new BorderLayout(0, 0));
        setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                new EmptyBorder(2, 4, 2, 8)));

        logArea.setEditable(false);
        logArea.setFont(MONO);
        logArea.setBackground(UIManager.getColor("TextArea.background"));
        logScroll = new JScrollPane(logArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        logScroll.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));
        logScroll.setVisible(false);

        toggleButton.setFocusable(false);
        toggleButton.putClientProperty("JButton.buttonType", "borderless");
        toggleButton.setFont(toggleButton.getFont().deriveFont(Font.BOLD, 11f));
        toggleButton.addActionListener(this::onToggle);

        currentLabel.setFont(currentLabel.getFont().deriveFont(Font.PLAIN, 11f));
        progressLabel.setFont(progressLabel.getFont().deriveFont(Font.PLAIN, 11f));
        progressLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        elapsedLabel.setFont(elapsedLabel.getFont().deriveFont(Font.PLAIN, 11f));
        elapsedLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerRight.setOpaque(false);
        headerRight.add(progressLabel);
        headerRight.add(elapsedLabel);

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.add(toggleButton, BorderLayout.WEST);
        header.add(currentLabel, BorderLayout.CENTER);
        header.add(headerRight,  BorderLayout.EAST);

        add(header,    BorderLayout.NORTH);
        add(logScroll, BorderLayout.CENTER);

        elapsedTimer = new Timer(1000, e -> updateElapsed());
        elapsedTimer.setRepeats(true);

        model.addPropertyChangeListener(this::onModelChange);
        setVisible(false);
    }

    // ── Event handlers ────────────────────────────────────────────────────

    private void onToggle(ActionEvent ignored) {
        logExpanded = !logExpanded;
        toggleButton.setText(logExpanded ? "▼ Activity" : "▶ Activity");
        logScroll.setVisible(logExpanded);
        revalidate();
        repaint();
    }

    private void onModelChange(PropertyChangeEvent evt) {
        switch (evt.getPropertyName()) {
            case "status" -> {
                AnalysisModel.Status s = (AnalysisModel.Status) evt.getNewValue();
                boolean busy = s == AnalysisModel.Status.SCANNING
                        || s == AnalysisModel.Status.AI_RUNNING;
                if (busy && !isVisible()) {
                    analysisStart = Instant.now();
                    logArea.setText("");
                    elapsedTimer.start();
                    setVisible(true);
                } else if (!busy) {
                    elapsedTimer.stop();
                    if (s == AnalysisModel.Status.DONE || s == AnalysisModel.Status.ERROR) {
                        updateElapsed();
                    }
                }
                if (s == AnalysisModel.Status.IDLE) {
                    setVisible(false);
                }
            }
            case "cleared" -> {
                logArea.setText("");
                currentLabel.setText("");
                progressLabel.setText("");
                elapsedLabel.setText("");
                setVisible(false);
            }
            case "currentAiClass" -> {
                String fqcn = (String) evt.getNewValue();
                String simple = simpleName(fqcn);
                currentLabel.setText(simple.isEmpty() ? "" : "→ " + simple);
            }
            case "progress" -> {
                AnalysisModel src = (AnalysisModel) evt.getSource();
                int cur = src.getProgressCurrent();
                int tot = src.getProgressTotal();
                progressLabel.setText(tot > 0 ? cur + " / " + tot : "");
            }
            case "aiClassDone" -> {
                AnalysisModel.AiClassResult r = (AnalysisModel.AiClassResult) evt.getNewValue();
                appendLog(r);
            }
            default -> { /* ignore */ }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Refreshes the elapsed-time label; called by the one-second timer. */
    private void updateElapsed() {
        if (analysisStart == null) { return; }
        long secs = java.time.Duration.between(analysisStart, Instant.now()).getSeconds();
        long h = secs / 3600;
        long m = secs % 3600 / 60;
        long s = secs % 60;
        elapsedLabel.setText(String.format("%02d:%02d:%02d", h, m, s));
    }

    /**
     * Appends a single line for {@code result} to the log area and
     * auto-scrolls to the bottom.
     */
    private void appendLog(AnalysisModel.AiClassResult r) {
        String icon     = r.hadError() ? "✗" : "✓";
        String duration = String.format("%.1f s", r.durationMs() / 1000.0);
        String line     = String.format("  %s  %-40s  %s, %d method(s)%n",
                icon, simpleName(r.fqcn()), duration, r.methodCount());
        logArea.append(line);
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** Returns the simple class name from a fully-qualified name. */
    private static String simpleName(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) { return ""; }
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }
}
