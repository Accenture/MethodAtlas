// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.receipt;

import java.util.List;

/**
 * Top-level reproducibility receipt written to disk by the {@code -emit-receipt}
 * mode.
 *
 * <p>
 * The receipt captures the SHA-256 fingerprints of every input that influenced
 * a scan so an auditor can decide whether a re-run would produce the same
 * output without repeating the scan. See the schema documentation in
 * {@code docs/usage-modes/reproducibility-receipts.md} for the full field
 * reference.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code receipt} package needs to
 * construct this directly; {@link ReceiptBuilder} is the sole producer and
 * {@link ReceiptWriter} the sole consumer.
 * </p>
 *
 * @param schemaVersion       receipt schema version; currently {@code "1"}
 * @param generatedUtc        ISO-8601 instant captured at receipt-creation time
 * @param methodAtlasVersion  tool version string, or {@code "dev"} when no
 *                            implementation version is set in the JAR manifest
 * @param javaVersion         value of the {@code java.version} system property
 *                            at receipt-creation time
 * @param outputMode          textual name of the {@link
 *                            org.egothor.methodatlas.emit.OutputMode} used
 *                            (e.g. {@code "SARIF"}, {@code "CSV"})
 * @param scanRoots           absolute paths of every scan root supplied on the
 *                            command line
 * @param deterministicReplay {@code true} when a re-run with the same source
 *                            files would necessarily produce identical output;
 *                            {@code false} when AI is enabled without a cache
 * @param inputs              {@link ReceiptInputs} structure with per-input
 *                            fingerprints; never {@code null}
 * @param configHash          SHA-256 hex of the canonical key=value
 *                            serialisation of the input fingerprints; an
 *                            auditor can recompute this from the other fields
 *                            to detect tampering
 */
/* default */ record ReproducibilityReceipt(
        String schemaVersion,
        String generatedUtc,
        String methodAtlasVersion,
        String javaVersion,
        String outputMode,
        List<String> scanRoots,
        boolean deterministicReplay,
        ReceiptInputs inputs,
        String configHash) {
}
