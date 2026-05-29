// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.emit;

import java.util.List;

import org.egothor.methodatlas.ai.AiMethodSuggestion;

/**
 * {@link TestMethodSink} that forwards every record to a fixed set of
 * delegate sinks.
 *
 * <p>
 * Used by the orchestration layer when an extra sink (e.g. the
 * {@code -emit-coverage} collector) needs to run alongside a command's
 * primary output sink without doubling the scan or coupling commands to
 * the secondary concern.
 * </p>
 *
 * <p>
 * Public so consumers in other modules (notably the root module's
 * orchestration layer and coverage collector) can compose sinks; the
 * constructor is exposed because there is no simpler call site than
 * {@code new CompositeTestMethodSink(a, b)}.
 * </p>
 */
public final class CompositeTestMethodSink implements TestMethodSink {

    /** Delegates invoked in declaration order. Defensive copy of the input. */
    private final TestMethodSink[] sinks;

    /**
     * Creates a fan-out sink wrapping the supplied delegates.
     *
     * @param sinks delegates to invoke for every record; must not be
     *              {@code null}; defensively copied so later mutations to the
     *              caller's array do not affect this sink
     */
    public CompositeTestMethodSink(TestMethodSink... sinks) {
        this.sinks = sinks.clone();
    }

    /**
     * Forwards a record to every wrapped sink in order.
     */
    @Override
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion) {
        for (TestMethodSink sink : sinks) {
            sink.record(fqcn, method, beginLine, loc, contentHash, tags, displayName, suggestion);
        }
    }
}
