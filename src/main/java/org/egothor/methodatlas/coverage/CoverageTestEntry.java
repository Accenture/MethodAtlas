// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One test method's contribution to the control-coverage report.
 *
 * <p>
 * Package-private because the type is part of the JSON contract emitted by
 * {@link ControlCoverageWriter} but never returned to callers outside the
 * {@code coverage} package.
 * </p>
 *
 * @param fqcn        fully qualified class name of the test method
 * @param method      test method name
 * @param displayName optional human-readable display name (from source
 *                    annotation or AI suggestion); {@code null} when absent
 * @param tags        merged list of mappable tags that link this test to one
 *                    or more controls; unmodifiable
 * @param tagSource   provenance of the merged tag list — one of
 *                    {@code "source"}, {@code "ai"}, or {@code "both"}
 * @param confidence  evidence confidence in {@code [0.0, 1.0]}; always
 *                    {@code 1.0} for source-derived evidence, equal to the
 *                    AI confidence score when {@code tagSource} is {@code "ai"}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
/* default */ record CoverageTestEntry(
        String fqcn,
        String method,
        String displayName,
        List<String> tags,
        String tagSource,
        double confidence) {
}
