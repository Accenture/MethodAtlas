// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One control's coverage record — chapter metadata plus the list of tests
 * that contribute evidence.
 *
 * <p>
 * Package-private because the type only exists to shape the JSON payload
 * written by {@link ControlCoverageWriter}.
 * </p>
 *
 * @param chapter      chapter label copied from the mapping file; may be
 *                     {@code null} when the mapping omits it
 * @param chapterTitle human-readable chapter title; may be {@code null}
 * @param tests        unmodifiable list of covering test entries; never
 *                     empty (controls with zero tests are emitted via the
 *                     {@code gaps} array instead)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
/* default */ record CoverageControlEntry(
        String chapter,
        String chapterTitle,
        List<CoverageTestEntry> tests) {
}
