package org.egothor.methodatlas.gui.model;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central observable model for the MethodAtlas GUI.
 *
 * <p>All mutating methods must be called on the Swing Event Dispatch Thread
 * (EDT).  {@link org.egothor.methodatlas.gui.service.AnalysisService}
 * publishes every update through
 * {@link javax.swing.SwingWorker#process SwingWorker.process}, which
 * already runs on the EDT, so callers outside the EDT must use
 * {@link javax.swing.SwingUtilities#invokeLater invokeLater}.
 * Observers attach via {@link #addPropertyChangeListener}.</p>
 *
 * <h2>Fired property names</h2>
 * <ul>
 *   <li>{@code "entries"} — one or more {@link MethodEntry} objects were
 *       added or had their AI suggestion updated; the new value is the
 *       affected {@code MethodEntry}</li>
 *   <li>{@code "status"} — the analysis {@link Status} changed; old and
 *       new values are the before/after {@code Status} constants</li>
 *   <li>{@code "statusMessage"} — the human-readable status text changed;
 *       the new value is the updated {@code String}</li>
 *   <li>{@code "progress"} — AI processing advanced; the old value is the
 *       previous {@link #getProgressCurrent()} and the new value is the
 *       current one</li>
 *   <li>{@code "currentAiClass"} — the FQCN of the class currently being
 *       sent to the AI engine changed; the new value is the FQCN
 *       {@code String}, or an empty string when no class is in flight</li>
 *   <li>{@code "aiClassDone"} — one class has finished AI enrichment;
 *       the new value is the completed {@link AiClassResult}</li>
 *   <li>{@code "selectedEntry"} — the user selected a different method;
 *       old and new values are the before/after {@link MethodEntry}
 *       instances (either may be {@code null})</li>
 *   <li>{@code "cleared"} — all entries were removed because a new scan
 *       was started; old value is {@code false}, new value is {@code true}</li>
 * </ul>
 *
 * @see org.egothor.methodatlas.gui.service.AnalysisService
 */
public final class AnalysisModel {

    /**
     * High-level lifecycle state of the background analysis.
     *
     * <p>States transition in a single direction during one analysis run:
     * {@code IDLE} → {@code SCANNING} → {@code AI_RUNNING} → {@code DONE}
     * (or {@code ERROR} on failure, or back to {@code IDLE} on
     * cancellation).</p>
     */
    public enum Status {

        /**
         * No analysis is in progress.
         *
         * <p>This is the initial state.  It is also set when an in-progress
         * analysis is cancelled by the user.</p>
         */
        IDLE,

        /**
         * File-system traversal and test-method discovery are in progress.
         *
         * <p>Methods are published to the model as they are found so that the
         * results tree populates incrementally without waiting for AI.</p>
         */
        SCANNING,

        /**
         * Discovery is complete; the AI engine is enriching methods class by class.
         *
         * <p>Progress is tracked by {@link #getProgressCurrent()} and
         * {@link #getProgressTotal()}.  The FQCN of the class currently being
         * processed is available from {@link #getCurrentAiClass()}.</p>
         */
        AI_RUNNING,

        /**
         * All phases have completed normally.
         *
         * <p>The model is now fully populated.  A subsequent call to
         * {@link #clear()} resets the model and returns it to
         * {@link #IDLE}.</p>
         */
        DONE,

        /**
         * The analysis terminated abnormally.
         *
         * <p>A partial result set may be present in the model.  The
         * human-readable error description is available from
         * {@link #getStatusMessage()}.</p>
         */
        ERROR
    }

    /**
     * Immutable result of AI enrichment for a single class.
     *
     * @param fqcn        fully-qualified name of the class that was processed
     * @param methodCount number of test methods the class contains
     * @param durationMs  wall-clock time elapsed during the AI call, in
     *                    milliseconds; {@code 0} when no AI call was made
     *                    (for example, because the source was unavailable)
     * @param hadError    {@code true} if the AI call failed with an
     *                    {@link org.egothor.methodatlas.ai.AiSuggestionException}
     */
    public record AiClassResult(String fqcn, int methodCount, long durationMs, boolean hadError) {}

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    /** Preserves insertion order so classes appear in discovery order. */
    private final Map<String, List<MethodEntry>> methodsByClass = new LinkedHashMap<>();

    private Status status = Status.IDLE;
    private String statusMessage = "Ready";
    private int progressCurrent;
    private int progressTotal;
    private String currentAiClass = "";
    private final List<AiClassResult> recentAiResults = new ArrayList<>();
    private MethodEntry selectedEntry;

    // ── Observer wiring ───────────────────────────────────────────────────

    /**
     * Registers a listener that is notified whenever any model property changes.
     *
     * @param l listener to register; must not be {@code null}
     * @see #addPropertyChangeListener(String, PropertyChangeListener)
     * @see #removePropertyChangeListener(PropertyChangeListener)
     */
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    /**
     * Registers a listener that is notified only when the named property changes.
     *
     * @param prop property name as documented in the class-level Javadoc;
     *             must not be {@code null}
     * @param l    listener to register; must not be {@code null}
     * @see #addPropertyChangeListener(PropertyChangeListener)
     */
    public void addPropertyChangeListener(String prop, PropertyChangeListener l) {
        pcs.addPropertyChangeListener(prop, l);
    }

    /**
     * Removes a previously registered listener.
     *
     * <p>If {@code l} was registered for a specific property, this method
     * removes only the global registration.  Use
     * {@link java.beans.PropertyChangeSupport#removePropertyChangeListener(String, PropertyChangeListener)}
     * directly for named-property listeners.</p>
     *
     * @param l listener to remove; ignored if {@code null} or not registered
     */
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }

    // ── Mutation ──────────────────────────────────────────────────────────

    /**
     * Removes all method entries and resets all progress counters to their
     * initial values.
     *
     * <p>This method is called at the beginning of each new analysis run.
     * Fires the {@code "cleared"} property change event.</p>
     */
    public void clear() {
        methodsByClass.clear();
        progressCurrent = 0;
        progressTotal = 0;
        currentAiClass = "";
        recentAiResults.clear();
        pcs.firePropertyChange("cleared", false, true);
    }

    /**
     * Appends a newly discovered method entry to the model.
     *
     * <p>Multiple entries for the same class are allowed and are stored in
     * discovery order.  Fires the {@code "entries"} property change event
     * with the new entry as the event's new value.</p>
     *
     * @param entry the discovered method entry to append; must not be
     *              {@code null}
     */
    public void addEntry(MethodEntry entry) {
        String fqcn = entry.discovered().fqcn();
        methodsByClass.computeIfAbsent(fqcn, k -> new ArrayList<>()).add(entry);
        pcs.firePropertyChange("entries", null, entry);
    }

    /**
     * Updates the AI suggestion on an existing entry for the given class and
     * method name.
     *
     * <p>The entry is located by matching both {@code fqcn} and
     * {@code methodName}.  If no matching entry is found, this method does
     * nothing.  Fires the {@code "entries"} property change event with the
     * updated entry as the event's new value.</p>
     *
     * @param fqcn       fully-qualified name of the class that owns the method;
     *                   must not be {@code null}
     * @param methodName simple name of the method to update; must not be
     *                   {@code null}
     * @param suggestion AI-generated suggestion to apply; may be {@code null}
     *                   to clear an existing suggestion
     */
    public void updateSuggestion(String fqcn, String methodName,
            org.egothor.methodatlas.ai.AiMethodSuggestion suggestion) {
        List<MethodEntry> entries = methodsByClass.get(fqcn);
        if (entries == null) return;
        for (MethodEntry e : entries) {
            if (e.discovered().method().equals(methodName)) {
                e.setSuggestion(suggestion);
                pcs.firePropertyChange("entries", null, e);
                return;
            }
        }
    }

    /**
     * Sets the high-level lifecycle status of the background analysis.
     *
     * <p>Fires the {@code "status"} property change event with the previous
     * and new {@link Status} values.</p>
     *
     * @param status the new lifecycle status; must not be {@code null}
     */
    public void setStatus(Status status) {
        Status old = this.status;
        this.status = status;
        pcs.firePropertyChange("status", old, status);
    }

    /**
     * Sets the human-readable status message shown in the status bar.
     *
     * <p>Fires the {@code "statusMessage"} property change event.</p>
     *
     * @param message the message text to display; must not be {@code null}
     */
    public void setStatusMessage(String message) {
        String old = this.statusMessage;
        this.statusMessage = message;
        pcs.firePropertyChange("statusMessage", old, message);
    }

    /**
     * Updates the AI enrichment progress counters.
     *
     * <p>Fires the {@code "progress"} property change event with the previous
     * and new values of {@code current}.</p>
     *
     * @param current number of classes for which AI enrichment has been
     *                initiated in this run (1-based; {@code 0} before the
     *                first class starts)
     * @param total   total number of classes that will be sent to the AI
     *                engine in this run; {@code 0} if not yet known
     */
    public void setProgress(int current, int total) {
        int old = this.progressCurrent;
        this.progressCurrent = current;
        this.progressTotal = total;
        pcs.firePropertyChange("progress", old, current);
    }

    /**
     * Sets the fully-qualified name of the class currently being sent to
     * the AI engine.
     *
     * <p>Pass an empty string (or {@code null}, which is normalised to an
     * empty string) when no class is in flight.  Fires the
     * {@code "currentAiClass"} property change event.</p>
     *
     * @param fqcn fully-qualified class name, or {@code null} / empty string
     *             to clear the indicator
     */
    public void setCurrentAiClass(String fqcn) {
        String old = this.currentAiClass;
        this.currentAiClass = fqcn == null ? "" : fqcn;
        pcs.firePropertyChange("currentAiClass", old, this.currentAiClass);
    }

    /**
     * Records the result of AI enrichment for one class and appends it to
     * the recent-results log.
     *
     * <p>The log is capped at fifty entries; the oldest entry is removed
     * when the cap is exceeded.  Fires the {@code "aiClassDone"} property
     * change event with {@code null} as the old value and {@code result}
     * as the new value.</p>
     *
     * @param result result of the completed AI call; must not be {@code null}
     */
    public void addAiClassResult(AiClassResult result) {
        recentAiResults.add(result);
        if (recentAiResults.size() > 50) recentAiResults.remove(0);
        pcs.firePropertyChange("aiClassDone", null, result);
    }

    /**
     * Changes the currently selected method entry.
     *
     * <p>Fires the {@code "selectedEntry"} property change event with the
     * previous and new entries (either may be {@code null}).</p>
     *
     * @param entry the newly selected entry, or {@code null} to deselect
     */
    public void setSelectedEntry(MethodEntry entry) {
        MethodEntry old = this.selectedEntry;
        this.selectedEntry = entry;
        pcs.firePropertyChange("selectedEntry", old, entry);
    }

    // ── Read access ───────────────────────────────────────────────────────

    /**
     * Returns the current lifecycle status of the background analysis.
     *
     * @return current {@link Status}; never {@code null}
     */
    public Status getStatus() { return status; }

    /**
     * Returns the human-readable status message last set by the analysis
     * service or by direct callers.
     *
     * @return current status message; never {@code null}
     */
    public String getStatusMessage() { return statusMessage; }

    /**
     * Returns the number of classes for which AI enrichment has been
     * initiated in the current run.
     *
     * @return classes started so far (1-based); {@code 0} before the AI
     *         phase begins or after {@link #clear()}
     */
    public int getProgressCurrent() { return progressCurrent; }

    /**
     * Returns the total number of classes that will be sent to the AI engine
     * in the current run.
     *
     * @return total class count; {@code 0} before the AI phase begins or
     *         after {@link #clear()}
     */
    public int getProgressTotal() { return progressTotal; }

    /**
     * Returns the fully-qualified name of the class currently being sent to
     * the AI engine.
     *
     * @return FQCN of the in-flight class, or an empty string when no class
     *         is being processed; never {@code null}
     */
    public String getCurrentAiClass() { return currentAiClass; }

    /**
     * Returns an unmodifiable view of the AI class results recorded so far
     * in the current run, in completion order.
     *
     * <p>The list contains at most fifty entries; older entries are dropped
     * when the cap is exceeded.</p>
     *
     * @return unmodifiable list of {@link AiClassResult} objects; never
     *         {@code null}
     */
    public List<AiClassResult> getRecentAiResults() {
        return Collections.unmodifiableList(recentAiResults);
    }

    /**
     * Returns the currently selected method entry.
     *
     * @return selected entry, or {@code null} when no entry is selected
     */
    public MethodEntry getSelectedEntry() { return selectedEntry; }

    /**
     * Returns an unmodifiable snapshot of the class-to-methods map in
     * discovery order.
     *
     * <p>The returned map reflects the state of the model at the time of the
     * call; subsequent changes to the model are not visible through it.</p>
     *
     * @return unmodifiable map from FQCN to the list of method entries for
     *         that class; never {@code null}
     */
    public Map<String, List<MethodEntry>> getMethodsByClass() {
        return Collections.unmodifiableMap(methodsByClass);
    }

    /**
     * Returns the total number of test methods discovered so far across all
     * classes.
     *
     * @return total method count; {@code 0} before discovery starts or after
     *         {@link #clear()}
     */
    public int getTotalMethodCount() {
        return methodsByClass.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Returns the number of distinct classes discovered so far.
     *
     * @return class count; {@code 0} before discovery starts or after
     *         {@link #clear()}
     */
    public int getClassCount() {
        return methodsByClass.size();
    }
}
