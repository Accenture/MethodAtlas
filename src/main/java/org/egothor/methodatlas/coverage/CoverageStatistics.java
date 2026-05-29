// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

/**
 * Aggregate counts derived from a control-coverage report.
 *
 * <p>
 * Package-private because the type only exists to shape the JSON payload
 * written by {@link ControlCoverageWriter}. The component
 * {@link #coveragePercent()} is pre-rounded by the collector to two decimal
 * places via the formula
 * {@code Math.round(ratio * 10_000.0) / 100.0}, where {@code ratio} is
 * {@code coveredControls / totalMappedControls} and the value is {@code 0.0}
 * when {@code totalMappedControls == 0}.
 * </p>
 *
 * @param totalMappedControls total distinct control IDs declared in the
 *                            mapping file (covered or not)
 * @param coveredControls     subset of {@code totalMappedControls} that
 *                            received at least one covering test
 * @param uncoveredControls   {@code totalMappedControls - coveredControls}
 * @param coveragePercent     {@code coveredControls / totalMappedControls}
 *                            expressed as a percentage rounded to two decimal
 *                            places
 */
/* default */ record CoverageStatistics(
        int totalMappedControls,
        int coveredControls,
        int uncoveredControls,
        double coveragePercent) {
}
