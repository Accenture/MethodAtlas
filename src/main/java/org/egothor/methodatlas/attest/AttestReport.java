// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.attest;

import java.util.List;

/**
 * Top-level attestation manifest written to disk by {@link AttestFacade}.
 *
 * <p>
 * The manifest is a compact, content-stable JSON file that records the
 * security-relevant test methods observed during a scan. It is intended to be
 * signed externally (e.g. with {@code cosign sign-blob} or GPG) and attached
 * to a release as tamper-evident evidence.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code attest} package needs to
 * construct or inspect the manifest directly; the JSON form is the contract.
 * </p>
 *
 * @param schemaVersion          manifest schema version; currently {@code "1"}
 * @param generatedUtc           ISO-8601 instant at manifest-creation time
 * @param toolVersion            tool version string, or {@code "dev"}
 * @param commitSha              commit SHA resolved from CI environment
 *                               variables, or {@code "unknown"}
 * @param scanRoots              list of scan root paths as strings
 * @param totalMethods           total test methods discovered (security-relevant
 *                               and otherwise)
 * @param securityRelevantMethods number of security-relevant methods included in
 *                               {@code methods}
 * @param methods                ordered list of security-relevant method entries
 */
/* default */ record AttestReport(
        String schemaVersion,
        String generatedUtc,
        String toolVersion,
        String commitSha,
        List<String> scanRoots,
        int totalMethods,
        int securityRelevantMethods,
        List<AttestEntry> methods) {
}
