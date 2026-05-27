// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.emit;

/**
 * Sealed marker interface implemented by every concrete emitter that
 * renders test-method records to a downstream representation (CSV, plain
 * text, SARIF, JSON, or GitHub Actions workflow annotations).
 *
 * <p>
 * The interface itself carries no methods: the four emitters have
 * deliberately different surfaces (the CSV/plain emitter is push-style
 * via {@code emit(...)}, the SARIF / JSON emitters buffer and then
 * flush, the GitHub-annotations emitter is a {@link TestMethodSink} that
 * streams). Forcing a common method shape across them would either
 * collapse this useful variation or invent a least-common-denominator
 * interface that no caller actually wants.
 * </p>
 *
 * <p>
 * What the seal buys is compile-time exhaustiveness across the closed
 * set of emitter implementations. {@code switch} expressions and
 * pattern-matching {@code instanceof} over {@code RecordEmitter} report
 * a missing case at build time the moment a new emitter is permitted.
 * Adding a future emitter is a deliberate two-step change: extend the
 * {@code permits} clause here, then update every site that pattern-
 * matches on the sum type.
 * </p>
 *
 * <h2>Streaming vs buffering</h2>
 *
 * <p>
 * Each emitter is one of two operational shapes:
 * </p>
 * <ul>
 *   <li><b>Streaming</b> — emits records as soon as they arrive
 *       ({@link OutputEmitter} for CSV/plain output;
 *       {@link GitHubAnnotationsEmitter} for {@code ::notice} /
 *       {@code ::warning} workflow commands). The output writer must
 *       be ready before the first record arrives.</li>
 *   <li><b>Buffering</b> — accumulates records during the scan and
 *       writes the whole document at the end ({@link SarifEmitter} for
 *       SARIF 2.1.0; {@link JsonEmitter} for the flat JSON array).
 *       Callers must call {@code flush(PrintWriter)} after the scan
 *       completes.</li>
 * </ul>
 *
 * <p>
 * This distinction is documented per emitter; it is not encoded in the
 * type system because the streaming/buffering choice is a property of
 * the output mode, not of polymorphism over a common operation.
 * </p>
 *
 * @see OutputEmitter
 * @see SarifEmitter
 * @see JsonEmitter
 * @see GitHubAnnotationsEmitter
 * @since 1.0.0
 */
public sealed interface RecordEmitter
        permits OutputEmitter, SarifEmitter, JsonEmitter, GitHubAnnotationsEmitter {
}
