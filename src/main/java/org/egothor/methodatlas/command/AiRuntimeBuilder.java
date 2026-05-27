// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.egothor.methodatlas.ai.AiSuggestionException;

/**
 * Constructs the per-run AI infrastructure: the suggestion engine, the result
 * cache, and the human-readable taxonomy descriptor.
 *
 * <p>
 * The builder is stateless and may be shared across commands. It returns
 * {@code null} from {@link #buildEngine} when AI is disabled — the
 * orchestration layer relies on this sentinel rather than building a no-op
 * engine, because most commands change behaviour entirely when AI is off
 * (no extra columns, no confidence threshold, no taxonomy in metadata).
 * </p>
 *
 * <h2>Failure handling</h2>
 *
 * <p>
 * Engine construction can fail when an external provider cannot be reached
 * or the configured model is unknown. Such failures are wrapped in an
 * {@link IllegalStateException}, because they indicate a misconfigured run
 * rather than a recoverable I/O fault: the user must change configuration
 * before retrying.
 * </p>
 *
 * <p>
 * Cache loading reads an arbitrary CSV produced by a previous run; a
 * malformed file is reported as {@link IllegalArgumentException} so that
 * the offending path appears in the user-facing CLI error.
 * </p>
 *
 * @see AiRuntime
 * @see AiSuggestionEngine
 * @see AiResultCache
 * @since 1.0.0
 */
public final class AiRuntimeBuilder {

    /**
     * Creates a new builder. Stateless; safe to share across commands.
     */
    public AiRuntimeBuilder() {
        // Intentionally empty; AiRuntimeBuilder is stateless.
    }

    /**
     * Creates the AI suggestion engine for the current run, or returns
     * {@code null} when AI support is disabled.
     *
     * <p>
     * The orchestration layer treats {@code null} as a sentinel meaning
     * "skip every AI step": no per-class submission, no taxonomy resolution,
     * no confidence filtering. Returning a no-op engine would force every
     * call site to check a different way; returning {@code null} keeps the
     * decision in one place.
     * </p>
     *
     * @param aiOptions AI configuration for the current run; must not be
     *                  {@code null}
     * @return initialised AI engine, or {@code null} when {@code aiOptions}
     *         reports AI as disabled
     * @throws IllegalStateException if engine initialisation fails — typically
     *                               an unknown provider or unreachable
     *                               endpoint
     */
    public AiSuggestionEngine buildEngine(AiOptions aiOptions) {
        if (!aiOptions.enabled()) {
            return null;
        }
        try {
            return new AiSuggestionEngineImpl(aiOptions);
        } catch (AiSuggestionException e) {
            throw new IllegalStateException("Failed to initialize AI engine", e);
        }
    }

    /**
     * Loads the AI result cache from a previous MethodAtlas CSV output, or
     * returns the empty no-op cache when no cache file was configured.
     *
     * <p>
     * The empty no-op cache is a sentinel: every {@code lookup} returns
     * absent and {@code isActive()} returns {@code false}, which lets the
     * scan loop short-circuit cache work entirely without per-call null
     * checks.
     * </p>
     *
     * @param cacheFile path to a previous run's CSV output (must include the
     *                  {@code content_hash} column), or {@code null} to
     *                  disable caching
     * @return loaded cache, or the empty no-op cache; never {@code null}
     * @throws IllegalArgumentException if the cache file exists but cannot
     *                                  be read or parsed
     */
    public AiResultCache buildCache(Path cacheFile) {
        if (cacheFile == null) {
            return AiResultCache.empty();
        }
        try {
            return AiResultCache.load(cacheFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load AI cache file: " + cacheFile, e);
        }
    }

    /**
     * Produces a human-readable string identifying which taxonomy
     * configuration is in effect, for inclusion in scan-metadata output.
     *
     * <p>
     * Output forms:
     * </p>
     * <ul>
     *   <li>{@code "n/a (AI disabled)"} when no AI engine is active for the run</li>
     *   <li>{@code "file:<absolute path>"} when {@code aiOptions} names an external
     *       taxonomy file</li>
     *   <li>{@code "built-in/<mode>"} (for example {@code built-in/default} or
     *       {@code built-in/optimized}) when no external file was supplied</li>
     * </ul>
     *
     * <p>
     * The string is intended for human eyes — auditors checking which
     * taxonomy a particular scan run used. It is emitted by
     * {@code -emit-metadata} into the CSV preamble and into SARIF run
     * properties.
     * </p>
     *
     * @param aiOptions AI configuration for the current run; must not be
     *                  {@code null}
     * @param aiActive  whether an AI engine is active for this run
     * @return taxonomy descriptor string; never {@code null}
     */
    public String resolveTaxonomyInfo(AiOptions aiOptions, boolean aiActive) {
        if (!aiActive) {
            return "n/a (AI disabled)";
        }
        if (aiOptions.taxonomyFile() != null) {
            return "file:" + aiOptions.taxonomyFile().toAbsolutePath();
        }
        return "built-in/" + aiOptions.taxonomyMode().name().toLowerCase(Locale.ROOT);
    }
}
