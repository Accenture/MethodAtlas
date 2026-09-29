// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gap;

import java.util.List;

/**
 * Per-domain coverage entry in the security-domain gap report.
 *
 * <p>
 * Package-private because the type only shapes the JSON payload produced by
 * {@link GapReportWriter}; it is never returned to callers outside the
 * {@code gap} package.
 * </p>
 *
 * @param count   number of security-relevant test methods covering this taxonomy domain
 * @param methods covering test methods; unmodifiable; empty when {@code count} is zero
 */
/* default */ record GapReportDomain(int count, List<GapReportMethod> methods) {
}
