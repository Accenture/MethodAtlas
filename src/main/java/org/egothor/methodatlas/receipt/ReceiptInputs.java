// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.receipt;

/**
 * Inputs section of a reproducibility receipt — exactly the set of files and
 * provider settings whose contents influence the scan outcome.
 *
 * <p>
 * Every field is nullable on purpose: components that did not contribute to
 * a particular run (e.g. no override file supplied, AI disabled) are
 * represented as {@code null} so the JSON writer can omit them via Jackson's
 * {@code NON_NULL} inclusion policy.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code receipt} package needs
 * to construct this directly; {@link ReceiptBuilder} is the sole producer.
 * </p>
 *
 * @param taxonomyFile       fingerprint of the external taxonomy file, or
 *                           {@code null} when a built-in taxonomy is used
 * @param builtInTaxonomy    name of the built-in taxonomy
 *                           ({@code DEFAULT}, {@code OPTIMIZED}, …) when no
 *                           external file is configured; {@code null} when
 *                           {@code taxonomyFile} is present
 * @param overrideFile       fingerprint of the YAML classification override
 *                           file, or {@code null} when none was supplied
 * @param aiCacheFile        fingerprint of the AI cache CSV, or {@code null}
 *                           when no cache was supplied
 * @param aiProvider         AI provider name (uppercase enum constant), or
 *                           {@code null} when AI is disabled
 * @param aiModel            provider-specific model identifier, or
 *                           {@code null} when AI is disabled
 * @param promptTemplateHash SHA-256 of the static prompt skeleton via
 *                           {@link org.egothor.methodatlas.ai.PromptBuilder#templateHash()},
 *                           or {@code null} when AI is disabled
 */
/* default */ record ReceiptInputs(
        FileArtifact taxonomyFile,
        String builtInTaxonomy,
        FileArtifact overrideFile,
        FileArtifact aiCacheFile,
        String aiProvider,
        String aiModel,
        String promptTemplateHash) {
}
