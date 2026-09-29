// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gap;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One test method's contribution to the security-domain gap report.
 *
 * <p>
 * Package-private because the type only shapes the JSON payload produced by
 * {@link GapReportWriter}; it is never returned to callers outside the
 * {@code gap} package.
 * </p>
 *
 * @param fqcn        fully qualified class name of the test method
 * @param method      test method name
 * @param displayName optional human-readable display name (from AI suggestion);
 *                    {@code null} when absent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
/* default */ record GapReportMethod(String fqcn, String method, String displayName) {
}
