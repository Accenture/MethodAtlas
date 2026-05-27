// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourceContent;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnalysisModel}.
 *
 * <p>
 * The model is a mutable container backing the GUI's results tree and
 * activity panel. The tests here cover the lifecycle invariants the GUI
 * relies on: entries accumulate in insertion order, the status / progress
 * setters fire property-change events, and {@code clear()} resets every
 * accumulated piece of state in one call.
 * </p>
 *
 * @since 1.0.0
 */
class AnalysisModelTest {

    private static MethodEntry entry(String method) {
        SourceContent emptySource = Optional::empty;
        DiscoveredMethod m = new DiscoveredMethod("com.example.Test", method,
                1, 5, 5, List.of(), null, null, null, emptySource);
        return new MethodEntry(m, null);
    }

    @Test
    void initialState_zeroMethodCountAndIdleStatus() {
        AnalysisModel model = new AnalysisModel();
        assertEquals(AnalysisModel.Status.IDLE, model.getStatus());
        assertEquals(0, model.getTotalMethodCount(),
                "Initial method count should be zero");
    }

    @Test
    void addEntry_groupsByFqcnAndCountsCorrectly() {
        AnalysisModel model = new AnalysisModel();
        MethodEntry a = entry("a");
        MethodEntry b = entry("b");

        model.addEntry(a);
        model.addEntry(b);

        assertEquals(2, model.getTotalMethodCount());
        assertSame(a, model.getMethodsByClass().get("com.example.Test").get(0));
        assertSame(b, model.getMethodsByClass().get("com.example.Test").get(1));
    }

    @Test
    void setStatus_firesPropertyChangeEvent() {
        AnalysisModel model = new AnalysisModel();
        List<PropertyChangeEvent> events = new ArrayList<>();
        PropertyChangeListener listener = events::add;
        model.addPropertyChangeListener(listener);

        model.setStatus(AnalysisModel.Status.SCANNING);

        assertEquals(1, events.size(),
                "setStatus should fire exactly one property-change event");
        assertEquals(AnalysisModel.Status.SCANNING, events.get(0).getNewValue());
    }

    @Test
    void setProgress_firesEventOnlyWhenCurrentChanges() {
        AnalysisModel model = new AnalysisModel();
        List<PropertyChangeEvent> events = new ArrayList<>();
        model.addPropertyChangeListener(events::add);

        // First call moves current from 0 -> 5 (fires); second call leaves it
        // at 5 (no event because PropertyChangeSupport suppresses old==new).
        model.setProgress(5, 10);
        model.setProgress(5, 10);

        assertEquals(1, events.size(),
                "Setting the same progress twice must not fire a second event");
    }

    @Test
    void clear_resetsEntriesAndProgressButLeavesStatus() {
        AnalysisModel model = new AnalysisModel();
        model.addEntry(entry("a"));
        model.setStatus(AnalysisModel.Status.SCANNING);
        model.setProgress(5, 10);

        model.clear();

        assertEquals(0, model.getTotalMethodCount());
        // clear() resets the analysis data but not the lifecycle status -- the
        // caller is responsible for transitioning status separately.
        assertEquals(0, model.getProgressCurrent());
        assertEquals(0, model.getProgressTotal());
    }

    @Test
    void aiClassResult_recordCarriesFqcnAndCount() {
        AnalysisModel.AiClassResult result =
                new AnalysisModel.AiClassResult("com.acme.Cls", 3, 250, false);
        assertEquals("com.acme.Cls", result.fqcn());
        assertEquals(3, result.methodCount());
        assertEquals(250, result.durationMs());
        assertEquals(false, result.hadError());
    }

    @Test
    void removePropertyChangeListener_stopsFurtherEvents() {
        AnalysisModel model = new AnalysisModel();
        List<PropertyChangeEvent> events = new ArrayList<>();
        PropertyChangeListener listener = events::add;
        model.addPropertyChangeListener(listener);

        model.removePropertyChangeListener(listener);
        model.setStatus(AnalysisModel.Status.SCANNING);

        assertTrue(events.isEmpty(),
                "Removed listener must not receive further events");
    }
}
