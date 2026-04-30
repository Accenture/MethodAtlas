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
 * <p>All mutating methods must be called on the Event Dispatch Thread.
 * Observers attach via {@link #addPropertyChangeListener}.</p>
 *
 * <h2>Fired property names</h2>
 * <ul>
 *   <li>{@code "entries"} — one or more {@link MethodEntry} objects were added or updated</li>
 *   <li>{@code "status"} — the analysis {@link Status} changed</li>
 *   <li>{@code "statusMessage"} — the human-readable status text changed</li>
 *   <li>{@code "progress"} — AI processing advanced (oldValue=prev, newValue=current class count)</li>
 *   <li>{@code "selectedEntry"} — the user selected a different method</li>
 *   <li>{@code "cleared"} — all entries were removed (new scan started)</li>
 * </ul>
 */
public final class AnalysisModel {

    /** High-level lifecycle state of the background analysis. */
    public enum Status {
        IDLE, SCANNING, AI_RUNNING, DONE, ERROR
    }

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    /** Preserves insertion order so classes appear in discovery order. */
    private final Map<String, List<MethodEntry>> methodsByClass = new LinkedHashMap<>();

    private Status status = Status.IDLE;
    private String statusMessage = "Ready";
    private int progressCurrent;
    private int progressTotal;
    private MethodEntry selectedEntry;

    // ── Observer wiring ───────────────────────────────────────────────────

    /** Registers a listener for all model events. */
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    /** Registers a listener for a specific property. */
    public void addPropertyChangeListener(String prop, PropertyChangeListener l) {
        pcs.addPropertyChangeListener(prop, l);
    }

    /** Removes a previously registered listener. */
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }

    // ── Mutation ──────────────────────────────────────────────────────────

    /**
     * Removes all entries and resets progress counters.
     * Fires {@code "cleared"}.
     */
    public void clear() {
        methodsByClass.clear();
        progressCurrent = 0;
        progressTotal = 0;
        pcs.firePropertyChange("cleared", false, true);
    }

    /**
     * Adds or replaces an entry.
     * Fires {@code "entries"}.
     *
     * @param entry the method entry to add
     */
    public void addEntry(MethodEntry entry) {
        String fqcn = entry.discovered().fqcn();
        methodsByClass.computeIfAbsent(fqcn, k -> new ArrayList<>()).add(entry);
        pcs.firePropertyChange("entries", null, entry);
    }

    /**
     * Updates the AI suggestion on an existing entry identified by
     * {@code fqcn + "#" + methodName}.
     * Fires {@code "entries"}.
     *
     * @param fqcn       class name
     * @param methodName method name
     * @param suggestion updated suggestion (may be {@code null})
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

    /** Updates the lifecycle status. Fires {@code "status"}. */
    public void setStatus(Status status) {
        Status old = this.status;
        this.status = status;
        pcs.firePropertyChange("status", old, status);
    }

    /** Updates the status message. Fires {@code "statusMessage"}. */
    public void setStatusMessage(String message) {
        String old = this.statusMessage;
        this.statusMessage = message;
        pcs.firePropertyChange("statusMessage", old, message);
    }

    /**
     * Updates AI enrichment progress. Fires {@code "progress"}.
     *
     * @param current classes processed so far
     * @param total   total classes to process
     */
    public void setProgress(int current, int total) {
        int old = this.progressCurrent;
        this.progressCurrent = current;
        this.progressTotal = total;
        pcs.firePropertyChange("progress", old, current);
    }

    /**
     * Changes the selected method. Fires {@code "selectedEntry"}.
     *
     * @param entry newly selected entry, or {@code null} to deselect
     */
    public void setSelectedEntry(MethodEntry entry) {
        MethodEntry old = this.selectedEntry;
        this.selectedEntry = entry;
        pcs.firePropertyChange("selectedEntry", old, entry);
    }

    // ── Read access ───────────────────────────────────────────────────────

    /** @return current lifecycle status */
    public Status getStatus() { return status; }

    /** @return current human-readable status message */
    public String getStatusMessage() { return statusMessage; }

    /** @return AI classes processed so far */
    public int getProgressCurrent() { return progressCurrent; }

    /** @return total AI classes to process */
    public int getProgressTotal() { return progressTotal; }

    /** @return currently selected method entry, or {@code null} */
    public MethodEntry getSelectedEntry() { return selectedEntry; }

    /**
     * Returns an unmodifiable snapshot of the class-to-methods map.
     *
     * @return class map in discovery order
     */
    public Map<String, List<MethodEntry>> getMethodsByClass() {
        return Collections.unmodifiableMap(methodsByClass);
    }

    /** @return total number of discovered methods */
    public int getTotalMethodCount() {
        return methodsByClass.values().stream().mapToInt(List::size).sum();
    }

    /** @return number of classes discovered */
    public int getClassCount() {
        return methodsByClass.size();
    }
}
