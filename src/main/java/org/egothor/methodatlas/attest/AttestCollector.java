// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.attest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.emit.TestMethodSink;

/**
 * Streaming sink that builds an {@link AttestReport} from scan records observed
 * during a single run.
 *
 * <p>
 * The collector implements {@link TestMethodSink} so the orchestration layer
 * can hand it the same record stream that drives CSV/SARIF emitters, without a
 * second scan and without coupling the emitters to attestation logic.
 * </p>
 *
 * <p>
 * Only AI-classified, security-relevant methods whose confidence meets the
 * configured threshold are included in the manifest. All other methods are
 * counted toward {@code totalMethods} but not listed.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code attest} package needs to
 * construct one; {@link AttestFacade} is the sole external entry point.
 * </p>
 */
final class AttestCollector implements TestMethodSink {

    /** Schema version of the produced manifest. */
    private static final String SCHEMA_VERSION = "1";

    /** Minimum AI confidence required to include a method in the manifest. */
    private final double minConfidence;

    /** Scan root paths forwarded to the report header. */
    private final List<String> scanRoots;

    /** Running count of all methods seen (security-relevant or not). */
    private final AtomicInteger totalSeen = new AtomicInteger();

    /** Accumulated security-relevant entries in encounter order. Thread-safe for parallel-AI mode. */
    private final List<AttestEntry> entries = new CopyOnWriteArrayList<>();

    /**
     * Creates a collector for the attestation manifest.
     *
     * @param minConfidence minimum AI confidence threshold; values below this are
     *                      not included in the manifest
     * @param scanRoots     scan root paths shown in the report header
     */
    /* default */ AttestCollector(double minConfidence, List<String> scanRoots) {
        this.minConfidence = minConfidence;
        this.scanRoots = List.copyOf(scanRoots);
    }

    /**
     * Records a test method for potential inclusion in the attestation manifest.
     *
     * @param fqcn        fully qualified class name
     * @param method      test method name
     * @param beginLine   ignored
     * @param loc         ignored
     * @param contentHash SHA-256 fingerprint of the enclosing class source, or
     *                    {@code null} when {@code -content-hash} was not used
     * @param tags        ignored (attestation relies on AI classification only)
     * @param displayName ignored
     * @param suggestion  optional AI classification
     */
    @Override
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void record(String fqcn, String method, int beginLine, int loc, String contentHash,
            List<String> tags, String displayName, AiMethodSuggestion suggestion) {
        totalSeen.incrementAndGet();
        if (suggestion == null || !suggestion.securityRelevant()) {
            return;
        }
        if (suggestion.confidence() < minConfidence) {
            return;
        }
        List<String> aiTags = suggestion.tags() != null
                ? List.copyOf(suggestion.tags()) : List.of();
        entries.add(new AttestEntry(fqcn, method, contentHash, aiTags));
    }

    /**
     * Builds the final {@link AttestReport}.
     *
     * @param toolVersion resolved tool version string; never {@code null}
     * @return populated attestation manifest
     */
    /* default */ AttestReport buildReport(String toolVersion) {
        String commitSha = CommitShaResolver.resolve();
        List<AttestEntry> snapshot = Collections.unmodifiableList(new ArrayList<>(entries));
        return new AttestReport(
                SCHEMA_VERSION,
                Instant.now().toString(),
                toolVersion,
                commitSha,
                scanRoots,
                totalSeen.get(),
                snapshot.size(),
                snapshot);
    }
}
