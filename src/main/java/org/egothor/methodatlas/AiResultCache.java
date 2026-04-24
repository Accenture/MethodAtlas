package org.egothor.methodatlas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;

/**
 * In-memory cache of AI classification results loaded from a previous MethodAtlas scan output.
 *
 * <p>Cache entries are keyed by {@code content_hash} — the per-class SHA-256 fingerprint
 * produced by the {@code -content-hash} flag. A cache hit means the class source is identical
 * to the previous run; the stored AI classification is reused without an API call.</p>
 *
 * <p>When the source CSV was produced without {@code -content-hash}, the {@code content_hash}
 * column is absent and no entries are loaded. All lookups return empty and the AI is called
 * normally for every class.</p>
 *
 * <p>Instances are obtained via {@link #load(Path)} or the no-op {@link #empty()} singleton.</p>
 *
 * @see MethodAtlasApp
 */
final class AiResultCache {

    private final Map<String, AiClassSuggestion> byHash;
    private int hits;
    private int misses;

    private AiResultCache(Map<String, AiClassSuggestion> byHash) {
        this.byHash = byHash;
    }

    /** Returns an empty cache that always produces misses. */
    /* default */ static AiResultCache empty() {
        return new AiResultCache(Map.of());
    }

    /**
     * Loads a cache from a MethodAtlas CSV output file.
     *
     * <p>Only rows with a non-empty {@code content_hash} value and a non-{@code null}
     * {@code ai_security_relevant} column (AI was enabled for that scan) are included.
     * Rows missing either field are silently skipped.</p>
     *
     * @param csvPath path to a MethodAtlas CSV produced with {@code -content-hash -ai}
     * @return loaded cache; never {@code null}
     * @throws IOException if the file cannot be read
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    /* default */ static AiResultCache load(Path csvPath) throws IOException {
        List<ScanRecord> records = DeltaReport.loadRecords(csvPath);

        Map<String, List<ScanRecord>> grouped = new HashMap<>();
        for (ScanRecord r : records) {
            if (r.contentHash() != null && !r.contentHash().isEmpty()
                    && r.aiSecurityRelevant() != null) {
                grouped.computeIfAbsent(r.contentHash(), k -> new ArrayList<>()).add(r);
            }
        }

        Map<String, AiClassSuggestion> cache = new HashMap<>(grouped.size() * 2);
        for (Map.Entry<String, List<ScanRecord>> entry : grouped.entrySet()) {
            List<AiMethodSuggestion> methods = new ArrayList<>(entry.getValue().size());
            for (ScanRecord r : entry.getValue()) {
                methods.add(new AiMethodSuggestion(
                        r.method(),
                        Boolean.TRUE.equals(r.aiSecurityRelevant()),
                        r.aiDisplayName(),
                        r.aiTags() != null ? r.aiTags() : List.of(),
                        r.aiReason(),
                        r.aiConfidence() != null ? r.aiConfidence() : 0.0,
                        r.aiInteractionScore() != null ? r.aiInteractionScore() : 0.0));
            }
            cache.put(entry.getKey(), new AiClassSuggestion(null, null, null, null, methods));
        }

        return new AiResultCache(cache);
    }

    /**
     * Returns the cached classification for the class with the given content hash,
     * or empty when the hash is absent from the cache.
     *
     * @param contentHash SHA-256 fingerprint of the class source, or {@code null}
     * @return cached suggestion, or empty on a miss or null hash
     */
    /* default */ Optional<AiClassSuggestion> lookup(String contentHash) {
        if (contentHash == null) {
            misses++;
            return Optional.empty();
        }
        AiClassSuggestion cached = byHash.get(contentHash);
        if (cached != null) {
            hits++;
        } else {
            misses++;
        }
        return Optional.ofNullable(cached);
    }

    /**
     * Returns {@code true} when this cache contains at least one entry.
     *
     * <p>When {@code false}, content hashes do not need to be computed for lookups
     * because all results would be misses regardless.</p>
     */
    /* default */ boolean isActive() {
        return !byHash.isEmpty();
    }

    /** Returns the number of successful cache lookups so far. */
    /* default */ int hits() {
        return hits;
    }

    /** Returns the number of unsuccessful cache lookups so far. */
    /* default */ int misses() {
        return misses;
    }
}
