// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import java.util.List;
import java.util.Map;

/**
 * Top-level control-coverage report written to disk by the
 * {@code -emit-coverage} mode.
 *
 * <p>
 * The report is a faithful projection of the user-authored mapping plus the
 * tests that were observed during the scan. The {@link #coverage()} map is
 * insertion-ordered (and pre-sorted lexicographically by control key) so
 * downstream GRC tooling sees a stable arrangement when diffing two reports.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code coverage} package needs
 * to construct or inspect the report directly; the JSON form is the contract.
 * </p>
 *
 * @param schemaVersion       receipt schema version; currently {@code "1"}
 * @param generatedUtc        ISO-8601 instant at report-creation time
 * @param methodAtlasVersion  tool version string, or {@code "dev"}
 * @param framework           framework label copied from the mapping file
 * @param frameworkVersion    framework version copied from the mapping file
 * @param mappingSource       absolute path of the loaded mapping file
 * @param coverage            insertion-ordered map of covering controls keyed
 *                            by {@code <FRAMEWORK>-<id>}; unmodifiable
 * @param gaps                lexicographically sorted list of control keys
 *                            with zero covering tests; unmodifiable
 * @param statistics          aggregate counts over {@code coverage} and
 *                            {@code gaps}
 */
/* default */ record ControlCoverageReport(
        String schemaVersion,
        String generatedUtc,
        String methodAtlasVersion,
        String framework,
        String frameworkVersion,
        String mappingSource,
        Map<String, CoverageControlEntry> coverage,
        List<String> gaps,
        CoverageStatistics statistics) {
}
