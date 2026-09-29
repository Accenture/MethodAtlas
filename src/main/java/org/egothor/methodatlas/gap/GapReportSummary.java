// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gap;

/**
 * Aggregate counts for the security-domain gap report.
 *
 * <p>
 * Package-private because the type only shapes the JSON payload produced by
 * {@link GapReportWriter}; it is never returned to callers outside the
 * {@code gap} package.
 * </p>
 *
 * <p>
 * {@link #coveragePercent()} is pre-rounded to two decimal places via
 * {@code Math.round(coveredDomains / (double) totalDomains * 10_000.0) / 100.0};
 * the value is {@code 0.0} when {@code totalDomains == 0}.
 * </p>
 *
 * @param totalDomains     total number of recognised taxonomy domains (currently 9)
 * @param coveredDomains   domains with at least one covering security test
 * @param uncoveredDomains {@code totalDomains - coveredDomains}
 * @param coveragePercent  {@code coveredDomains / totalDomains} expressed as a
 *                         percentage rounded to two decimal places
 */
/* default */ record GapReportSummary(
        int totalDomains,
        int coveredDomains,
        int uncoveredDomains,
        double coveragePercent) {
}
