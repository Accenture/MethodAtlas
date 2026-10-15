package org.egothor.methodatlas.command;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.TaxonomyVersion;

/**
 * Computes the signature that keys AI result cache entries.
 *
 * <p>
 * Extracted from {@link ScanOrchestrator} to keep that class within the
 * complexity budget; the produced value is part of the cache contract and must
 * stay {@code <promptSignature>|<taxonomyVersion>}.
 * </p>
 */
/* default */ final class AiCacheKey {

    private AiCacheKey() {
    }

    /**
     * Computes the AI cache key signature for a run: the prompt-catalogue signature
     * joined with the resolved taxonomy version.
     *
     * <p>
     * Folding the taxonomy in makes a taxonomy change a cache miss, so every cache
     * hit was classified under the current taxonomy and the {@code ai_taxonomy_version}
     * stamped on emitted records is truthful.
     * </p>
     *
     * @param aiOptions AI options of the current run; must not be {@code null}
     * @return {@code <promptSignature>|<taxonomyVersion>}; never {@code null}
     * @throws IOException if a configured custom taxonomy file cannot be read
     */
    /* default */ static String signature(AiOptions aiOptions) throws IOException {
        return aiOptions.promptTemplates().signature() + "|" + TaxonomyVersion.resolve(aiOptions);
    }

    /**
     * Same as {@link #signature(AiOptions)} but rethrows I/O failures unchecked,
     * for use inside lambdas and stream pipelines.
     *
     * @param aiOptions AI options of the current run; must not be {@code null}
     * @return the cache key signature; never {@code null}
     * @throws UncheckedIOException if a configured custom taxonomy file cannot be read
     */
    /* default */ static String signatureUnchecked(AiOptions aiOptions) {
        try {
            return signature(aiOptions);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot resolve taxonomy version for AI cache key", e);
        }
    }
}
