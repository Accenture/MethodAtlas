package org.egothor.methodatlas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.CredentialTriageVerdict;
import org.egothor.methodatlas.api.ScanRecord;
import org.egothor.methodatlas.emit.DeltaReport;

/**
 * In-memory cache of AI results loaded from a previous MethodAtlas run, keyed by
 * the per-class {@code content_hash} fingerprint.
 *
 * <p>
 * Each entry ({@link AiCacheEntry}) holds the complete AI answer for one class —
 * method classifications <em>and</em> any credential-triage verdicts — plus the
 * prompt-catalogue signature that produced it. This lets one cached answer serve a
 * later classification-only run, a later credential run, or a combined run without
 * re-querying the model, provided the prompt signature still matches. An answer
 * obtained under a different prompt catalogue is never reused.
 * </p>
 *
 * <p>
 * Two source formats are accepted by {@link #load(Path)}: the unified JSON-Lines
 * cache (the format MethodAtlas now writes) and the legacy per-method scan CSV
 * (produced by older {@code -content-hash} runs). Legacy entries carry no prompt
 * signature and no credential verdicts: their classifications may still be reused
 * by content hash, but they can never satisfy a credential query.
 * </p>
 *
 * <p>
 * Instances are obtained via {@link #load(Path)} or the no-op {@link #empty()}.
 * The hit/miss counters are {@link AtomicInteger}s, so lookups remain correct if
 * the scan loop is ever parallelised; the entry map is immutable after loading.
 * </p>
 *
 * @see AiCacheStore
 * @see MethodAtlasApp
 */
public final class AiResultCache {

    private final Map<String, AiCacheEntry> byHash;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();

    private AiResultCache(Map<String, AiCacheEntry> byHash) {
        this.byHash = byHash;
    }

    /** Returns an empty cache that always produces misses. */
    public static AiResultCache empty() {
        return new AiResultCache(Map.of());
    }

    /**
     * Loads a cache from a unified JSON-Lines cache file or a legacy scan CSV,
     * auto-detected from the file's first non-blank character.
     *
     * @param path path to a unified cache file or a legacy MethodAtlas CSV
     * @return loaded cache; never {@code null}
     * @throws IOException if the file cannot be read
     */
    public static AiResultCache load(Path path) throws IOException {
        Map<String, AiCacheEntry> byHash = new HashMap<>();
        // Read the file once and drive both the format sniff and the parse from the
        // same in-memory copy (avoids a second full read for the JSON-Lines case).
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (AiCacheStore.looksLikeJsonLines(lines)) {
            for (AiCacheEntry entry : AiCacheStore.read(lines)) {
                if (entry.contentHash() != null && !entry.contentHash().isEmpty()
                        && entry.suggestion() != null) {
                    byHash.put(entry.contentHash(), entry);
                }
            }
        } else {
            loadLegacyCsv(path, byHash);
        }
        return new AiResultCache(byHash);
    }

    /**
     * Loads legacy entries from a per-method scan CSV. Only rows with a non-empty
     * {@code content_hash} and a non-{@code null} {@code ai_security_relevant}
     * column (AI was enabled) are included; the resulting entries carry no prompt
     * signature and no credential verdicts.
     *
     * @param csvPath legacy CSV path
     * @param byHash   map to populate, keyed by content hash
     * @throws IOException if the file cannot be read
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static void loadLegacyCsv(Path csvPath, Map<String, AiCacheEntry> byHash) throws IOException {
        List<ScanRecord> records = DeltaReport.loadRecords(csvPath);

        Map<String, List<ScanRecord>> grouped = new HashMap<>();
        for (ScanRecord r : records) {
            if (r.contentHash() != null && !r.contentHash().isEmpty() && r.aiSecurityRelevant() != null) {
                grouped.computeIfAbsent(r.contentHash(), k -> new ArrayList<>()).add(r);
            }
        }

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
            AiClassSuggestion suggestion = new AiClassSuggestion(null, null, null, null, methods);
            byHash.put(entry.getKey(), new AiCacheEntry(entry.getKey(), null, suggestion));
        }
    }

    /**
     * Returns the cached AI answer for a class by content hash, ignoring the prompt
     * signature.
     *
     * @param contentHash SHA-256 fingerprint of the class source, or {@code null}
     * @return cached suggestion, or empty on a miss or {@code null} hash
     */
    public Optional<AiClassSuggestion> lookup(String contentHash) {
        if (contentHash == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        AiCacheEntry entry = byHash.get(contentHash);
        if (entry != null) {
            hits.incrementAndGet();
            return Optional.of(entry.suggestion());
        }
        misses.incrementAndGet();
        return Optional.empty();
    }

    /**
     * Returns the cached classification for a class when the content hash matches and
     * the cached answer is compatible with the current prompt catalogue.
     *
     * <p>
     * A unified entry must carry a matching {@code promptSignature}; a legacy entry
     * (no signature) is served by content hash alone, preserving prior behaviour.
     * Updates the hit/miss counters.
     * </p>
     *
     * @param contentHash     SHA-256 fingerprint of the class source, or {@code null}
     * @param promptSignature signature of the current run's prompt catalogue
     * @return cached classification, or empty on a miss
     */
    public Optional<AiClassSuggestion> classification(String contentHash, String promptSignature) {
        AiCacheEntry entry = contentHash == null ? null : byHash.get(contentHash);
        if (entry != null && (entry.promptSignature() == null
                || entry.promptSignature().equals(promptSignature))) {
            hits.incrementAndGet();
            return Optional.of(entry.suggestion());
        }
        misses.incrementAndGet();
        return Optional.empty();
    }

    /**
     * Returns cached credential-triage verdicts for a class when the content hash and
     * the prompt signature both match and verdicts were actually recorded.
     *
     * <p>
     * Unlike {@link #classification(String, String)} this requires a non-{@code null}
     * matching signature (a legacy entry can never satisfy a credential query) and
     * does not touch the hit/miss counters.
     * </p>
     *
     * @param contentHash     SHA-256 fingerprint of the class source, or {@code null}
     * @param promptSignature signature of the current run's prompt catalogue
     * @return cached verdicts, or empty when none are cached for this signature
     */
    public Optional<List<CredentialTriageVerdict>> verdicts(String contentHash, String promptSignature) {
        AiCacheEntry entry = contentHash == null ? null : byHash.get(contentHash);
        if (entry != null && entry.promptSignature() != null
                && entry.promptSignature().equals(promptSignature)
                && entry.suggestion().secrets() != null) {
            return Optional.of(entry.suggestion().secrets());
        }
        return Optional.empty();
    }

    /**
     * Returns {@code true} when this cache contains at least one entry.
     *
     * <p>When {@code false}, content hashes do not need to be computed for lookups
     * because all results would be misses regardless.</p>
     *
     * @return {@code true} when the cache is non-empty
     */
    public boolean isActive() {
        return !byHash.isEmpty();
    }

    /** Returns the number of successful cache lookups so far. */
    public int hits() {
        return hits.get();
    }

    /** Returns the number of unsuccessful cache lookups so far. */
    public int misses() {
        return misses.get();
    }
}
