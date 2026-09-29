// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gap;

import java.util.List;
import java.util.Map;

/**
 * Top-level security-domain gap report written to disk by the {@code -gap-report} mode.
 *
 * <p>
 * The report lists each of the nine built-in taxonomy domains with the count of
 * covering security tests and the names of those tests. Domains with zero covering
 * tests are also collected in {@link #gaps()} for quick auditability.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code gap} package needs to construct
 * or inspect the report directly; the JSON form written by {@link GapReportWriter}
 * is the contract.
 * </p>
 *
 * @param schemaVersion       report schema version; currently {@code "1"}
 * @param generatedUtc        ISO-8601 instant at report-creation time
 * @param methodAtlasVersion  tool version string, or {@code "dev"}
 * @param domains             insertion-ordered map of taxonomy tag to per-domain
 *                            coverage entry; all nine canonical tags are always
 *                            present (count may be zero); unmodifiable
 * @param gaps                tags from {@code domains} whose {@link GapReportDomain#count()}
 *                            is zero, in canonical taxonomy order; unmodifiable
 * @param summary             aggregate counts over all domains
 */
/* default */ record GapReport(
        String schemaVersion,
        String generatedUtc,
        String methodAtlasVersion,
        Map<String, GapReportDomain> domains,
        List<String> gaps,
        GapReportSummary summary) {
}
