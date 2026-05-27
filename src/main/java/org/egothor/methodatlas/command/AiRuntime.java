// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.ClassificationOverride;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiSuggestionEngine;

/**
 * Bundle of AI infrastructure that is constant for the duration of a scan run.
 *
 * <p>
 * The {@link AiSuggestionEngine}, {@link ClassificationOverride}, and
 * {@link AiResultCache} for a single CLI invocation are all built once during
 * argument parsing and then handed to the {@link Command} implementations.
 * Packaging them as one immutable record keeps the per-class scan loop's
 * method signatures short and prevents accidental substitution of a
 * different cache or override partway through a run.
 * </p>
 *
 * @param options  AI configuration for the current run; never {@code null}
 * @param engine   AI engine providing per-class suggestions, or {@code null}
 *                 when AI support is disabled
 * @param override human-reviewed classification overrides; the empty
 *                 singleton when no override file was supplied
 * @param cache    AI result cache keyed by content hash; the empty
 *                 no-op cache when caching was not enabled
 * @since 1.0.0
 */
public record AiRuntime(AiOptions options, AiSuggestionEngine engine,
        ClassificationOverride override, AiResultCache cache) {
}
