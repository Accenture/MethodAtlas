// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Single mapping entry from one taxonomy tag to one control requirement.
 *
 * <p>
 * Package-private because mapping data is exposed to the rest of the tool via
 * {@link ControlMapping} only; nothing outside the {@code coverage} package
 * constructs {@code ControlEntry} instances directly.
 * </p>
 *
 * @param id           bare requirement ID (e.g. {@code "4.1.1"}); must be
 *                     non-blank
 * @param chapter      chapter label (e.g. {@code "V4"}); may be {@code null}
 *                     when the mapping file omits it
 * @param chapterTitle human-readable chapter name; may be {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
/* default */ record ControlEntry(String id, String chapter, String chapterTitle) {
}
