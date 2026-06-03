// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.ai;

/**
 * Callback invoked by an {@link AiSuggestionEngine} after each successful
 * AI round-trip so that observers (notably the evidence-pack archive) can
 * capture provenance about the call.
 *
 * <p>
 * The engine invokes {@link #onResponse} exactly once per provider call
 * regardless of provider or transport. The listener is provided with the
 * content-hash key used for cache lookups, the fully qualified class name,
 * the rendered prompt and raw response, and the operative model identifier
 * along with rough token counts when the provider reports them.
 * </p>
 *
 * <p>
 * The interface is intentionally minimal so that the engine can populate
 * its arguments without coupling to caller-specific data structures.
 * Implementations must be safe to invoke from the engine's calling thread
 * but are not required to be thread-safe.
 * </p>
 *
 * @see AiSuggestionEngine
 */
@FunctionalInterface
public interface AiResponseListener {

    /**
     * Receives one AI response record.
     *
     * @param contentHash    SHA-256 fingerprint used as the cache key for the
     *                       analysed class; may be {@code null} when the engine
     *                       did not compute a hash for this call
     * @param fqcn           fully qualified name of the class submitted to the AI
     * @param prompt         text of the prompt sent to the provider
     * @param response       raw text returned by the provider
     * @param modelId        provider-specific model identifier; may be
     *                       {@code null} when not known
     * @param promptTokens   approximate number of tokens consumed by the prompt;
     *                       {@code -1} when not reported by the provider
     * @param responseTokens approximate number of tokens consumed by the
     *                       response; {@code -1} when not reported by the
     *                       provider
     */
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    void onResponse(String contentHash, String fqcn,
            String prompt, String response,
            String modelId, int promptTokens, int responseTokens);
}
