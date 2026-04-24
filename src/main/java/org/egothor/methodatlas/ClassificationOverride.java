package org.egothor.methodatlas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Applies human-authored classification overrides to AI-generated (or absent)
 * security classification results.
 *
 * <h2>Purpose</h2>
 *
 * <p>
 * AI classification is a best-effort semantic analysis. In practice, a provider
 * may under-classify a method that is clearly security-relevant, over-classify a
 * utility method, assign incorrect tags, or produce a rationale that does not
 * meet audit requirements. {@code ClassificationOverride} allows a team or
 * individual to record corrections in a persistent YAML file so that re-running
 * MethodAtlas does not lose those decisions.
 * </p>
 *
 * <p>
 * Overrides are also the only mechanism for adding AI-style enrichment columns
 * to methods that are not going through live AI or the manual workflow — for
 * example, when running in static inventory mode with a trusted set of
 * hand-reviewed classifications.
 * </p>
 *
 * <h2>Override File Format</h2>
 *
 * <p>
 * The override file is a YAML document with a top-level {@code overrides} list.
 * Each entry targets either a single method (when {@code method} is present) or
 * every method in a class (when {@code method} is absent). Only the fields you
 * specify are overridden; unspecified fields retain their AI-derived or
 * default values.
 * </p>
 *
 * <pre>
 * overrides:
 *
 *   # Correct a false positive: AI classified this as security-relevant but it is not.
 *   - fqcn: com.acme.util.DateFormatterTest
 *     method: format_returnsIso8601
 *     securityRelevant: false
 *     reason: "Date formatting only — no security property tested"
 *     note: "Reviewed 2026-04-24 by alice"
 *
 *   # Correct a false negative: AI missed this security-critical test.
 *   - fqcn: com.acme.crypto.AesGcmTest
 *     method: roundTrip_encryptDecrypt
 *     securityRelevant: true
 *     tags: [security, crypto]
 *     displayName: "SECURITY: crypto — AES-GCM round-trip"
 *     reason: "Verifies ciphertext integrity under AES-GCM — critical crypto test"
 *     note: "Confirmed by security team 2026-04-20"
 *
 *   # Classify all methods in a class (no 'method' field = class-level override).
 *   - fqcn: com.acme.auth.Oauth2FlowTest
 *     securityRelevant: true
 *     tags: [security, auth]
 *     note: "Entire class covers OAuth 2.0 flow — AI taxonomy too narrow"
 * </pre>
 *
 * <h2>Field Reference</h2>
 *
 * <ul>
 * <li>{@code fqcn} — fully qualified class name; required; must match the
 *     {@code fqcn} column in MethodAtlas output</li>
 * <li>{@code method} — method name; optional; when absent the override applies
 *     to all methods in the class; method-level overrides take precedence over
 *     class-level overrides for the same class</li>
 * <li>{@code securityRelevant} — {@code true} or {@code false}; optional; when
 *     absent the AI decision (or default {@code false}) is kept</li>
 * <li>{@code tags} — YAML list of security taxonomy tags; optional; when absent
 *     the AI tags (or an empty list) are kept</li>
 * <li>{@code displayName} — suggested {@code @DisplayName} value; optional;
 *     when absent the AI-suggested name (or {@code null}) is kept</li>
 * <li>{@code reason} — human-readable rationale for the classification;
 *     optional; when absent the AI rationale (or {@code null}) is kept</li>
 * <li>{@code note} — free-text annotation for human use only; never appears in
 *     any MethodAtlas output; useful for recording reviewer identity, date, and
 *     decision context</li>
 * </ul>
 *
 * <h2>Confidence Behaviour</h2>
 *
 * <p>
 * When any override field is applied to a method, the output confidence value
 * is set to {@code 1.0} if the method is classified as security-relevant, or
 * {@code 0.0} otherwise. This reflects the fact that a human review provides
 * higher certainty than any AI score and ensures that confidence-based filters
 * (such as {@code --min-confidence}) do not suppress human-verified results.
 * </p>
 *
 * <h2>Integration Points</h2>
 *
 * <p>
 * {@code ClassificationOverride} works in all MethodAtlas operating modes:
 * </p>
 *
 * <ul>
 * <li><b>Live AI mode</b> ({@code -ai}) — AI result is obtained first;
 *     overrides are applied on top.</li>
 * <li><b>Manual AI workflow</b> ({@code -manual-consume}) — operator-filled
 *     responses are loaded first; overrides are applied on top.</li>
 * <li><b>Static mode</b> (no {@code -ai}) — no AI result exists; any override
 *     that marks a method as security-relevant synthesizes a full
 *     {@link AiMethodSuggestion} from the override fields alone.</li>
 * </ul>
 *
 * <h2>Unknown Methods</h2>
 *
 * <p>
 * Override entries that reference a method name not found in the parsed source
 * are silently ignored. This means old entries remain harmless after methods are
 * renamed or deleted, and the file does not need to be pruned after refactoring.
 * </p>
 *
 * @see AiClassSuggestion
 * @see AiMethodSuggestion
 */
public final class ClassificationOverride {

    private static final Logger LOG = Logger.getLogger(ClassificationOverride.class.getName());

    /**
     * Singleton instance used when no override file is configured. All calls to
     * {@link #apply} return the original suggestion unchanged.
     */
    private static final ClassificationOverride EMPTY = new ClassificationOverride(Map.of());

    /**
     * Override entries grouped by fully qualified class name for O(1) lookup.
     */
    private final Map<String, List<Entry>> byClass;

    private ClassificationOverride(Map<String, List<Entry>> byClass) {
        this.byClass = byClass;
    }

    /**
     * Returns an empty override set that leaves all classifications unchanged.
     *
     * <p>
     * Use this when no override file is configured.
     * </p>
     *
     * @return shared empty instance
     */
    public static ClassificationOverride empty() {
        return EMPTY;
    }

    /**
     * Loads an override file from the given path.
     *
     * <p>
     * The file must be a YAML document with a top-level {@code overrides} list.
     * See the class Javadoc for the expected structure. Unknown YAML fields are
     * silently ignored, so the file can carry additional human-readable metadata
     * beyond the recognized fields without causing parse errors.
     * </p>
     *
     * @param path path to the YAML override file
     * @return loaded override set; never {@code null}
     * @throws IOException if the file cannot be read or contains invalid YAML
     */
    public static ClassificationOverride load(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OverrideFile file = mapper.readValue(path.toFile(), OverrideFile.class);

        if (file.overrides == null || file.overrides.isEmpty()) {
            return EMPTY;
        }

        Map<String, List<Entry>> byClass = new HashMap<>();
        for (EntryDto dto : file.overrides) {
            if (dto.fqcn == null || dto.fqcn.isBlank()) {
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.warning("Override entry without fqcn skipped");
                }
                continue;
            }
            byClass.computeIfAbsent(dto.fqcn, k -> new ArrayList<>())
                    .add(new Entry(dto.fqcn, dto.method, dto.securityRelevant,
                            dto.tags, dto.displayName, dto.reason, dto.note));
        }

        return new ClassificationOverride(byClass);
    }

    /**
     * Returns {@code true} if at least one override entry targets the given class.
     *
     * <p>
     * This can be used to decide whether {@link #apply} should be called even
     * when no AI suggestion was produced (e.g. in static mode), avoiding
     * unnecessary processing for classes that have no overrides.
     * </p>
     *
     * @param fqcn fully qualified class name to check
     * @return {@code true} if overrides exist for {@code fqcn}
     */
    public boolean hasOverridesFor(String fqcn) {
        return byClass.containsKey(fqcn);
    }

    /**
     * Applies override entries to an existing AI classification result.
     *
     * <p>
     * The {@code methodNames} list must contain the canonical method names as
     * discovered by the MethodAtlas parser. It drives the set of methods for
     * which output records are produced; override entries targeting method names
     * absent from this list are silently skipped.
     * </p>
     *
     * <p>
     * When {@code suggestion} is {@code null} and no overrides target
     * {@code fqcn}, this method returns {@code null} unchanged so that the
     * absence of AI data is preserved correctly in the output.
     * </p>
     *
     * <p>
     * When {@code suggestion} is {@code null} but at least one override targets
     * {@code fqcn}, a synthetic {@link AiClassSuggestion} is constructed from
     * the override fields. Methods not targeted by any override will have
     * {@code securityRelevant=false} and empty tag/reason fields in the
     * synthesized result.
     * </p>
     *
     * @param fqcn        fully qualified class name of the class being processed
     * @param suggestion  AI classification result to modify; may be {@code null}
     * @param methodNames names of all test methods found by the parser in this
     *                    class, in discovery order
     * @return modified or synthesized classification; {@code null} only when both
     *         {@code suggestion} is {@code null} and no overrides target
     *         {@code fqcn}
     */
    public AiClassSuggestion apply(String fqcn, AiClassSuggestion suggestion, List<String> methodNames) {
        List<Entry> entries = byClass.get(fqcn);

        if (entries == null || entries.isEmpty()) {
            return suggestion;
        }

        // Separate class-level override (no method specified) from method-level entries.
        // If multiple class-level entries exist for the same FQCN, the last one wins.
        Entry classLevel = null;
        Map<String, Entry> methodLevel = new HashMap<>();
        for (Entry e : entries) {
            if (e.method() == null) {
                classLevel = e;
            } else {
                methodLevel.put(e.method(), e);
            }
        }

        // Build a name → existing suggestion map for quick lookup.
        List<AiMethodSuggestion> existingMethods = (suggestion != null && suggestion.methods() != null)
                ? suggestion.methods() : List.of();
        Map<String, AiMethodSuggestion> existingByName = new HashMap<>();
        for (AiMethodSuggestion m : existingMethods) {
            existingByName.put(m.methodName(), m);
        }

        // Apply overrides to each method found by the parser.
        List<AiMethodSuggestion> merged = new ArrayList<>(methodNames.size());
        for (String name : methodNames) {
            AiMethodSuggestion base = existingByName.get(name);
            Entry effective = methodLevel.getOrDefault(name, classLevel);
            merged.add(mergeMethod(name, base, effective));
        }

        // Class-level suggestion fields are carried through from the original,
        // or left null when no AI suggestion was available.
        return new AiClassSuggestion(
                suggestion != null ? suggestion.className() : fqcn,
                suggestion != null ? suggestion.classSecurityRelevant() : null,
                suggestion != null ? suggestion.classTags() : null,
                suggestion != null ? suggestion.classReason() : null,
                merged);
    }

    /**
     * Merges a single method's base classification with the applicable override
     * entry.
     *
     * <p>
     * Fields present in the override replace the corresponding base values.
     * Fields absent in the override retain their base values (or defaults when
     * no base classification exists). When any override field is applied, the
     * confidence is set to {@code 1.0} for security-relevant results and
     * {@code 0.0} otherwise, reflecting the higher certainty of human review.
     * </p>
     *
     * @param name     method name
     * @param base     existing AI suggestion for this method; may be {@code null}
     * @param override override entry to apply; may be {@code null} (no-op)
     * @return resulting method suggestion; never {@code null}
     */
    private static AiMethodSuggestion mergeMethod(String name, AiMethodSuggestion base, Entry override) {
        if (override == null) {
            // No override — synthesize a neutral record if base is absent.
            if (base != null) {
                return base;
            }
            return new AiMethodSuggestion(name, false, null, List.of(), null, 0.0, 0.0);
        }

        boolean securityRelevant = base != null ? base.securityRelevant() : false;
        List<String> tags = (base != null && base.tags() != null) ? base.tags() : List.of();
        String displayName = base != null ? base.displayName() : null;
        String reason = base != null ? base.reason() : null;

        if (override.securityRelevant() != null) {
            securityRelevant = override.securityRelevant();
        }
        if (override.tags() != null) {
            tags = List.copyOf(override.tags());
        }
        if (override.displayName() != null) {
            displayName = override.displayName();
        }
        if (override.reason() != null) {
            reason = override.reason();
        }

        // Human review supersedes any AI confidence score.
        // Interaction score is AI-generated and is preserved from the base suggestion.
        double confidence = securityRelevant ? 1.0 : 0.0;
        double interactionScore = base != null ? base.interactionScore() : 0.0;

        return new AiMethodSuggestion(name, securityRelevant, displayName, tags, reason, confidence, interactionScore);
    }

    // -------------------------------------------------------------------------
    // Public immutable entry type
    // -------------------------------------------------------------------------

    /**
     * A single override entry as stored in the in-memory index.
     *
     * <p>
     * All fields except {@link #fqcn} are optional and carry {@code null} to
     * indicate "not overridden". The {@link #note} field is never emitted in any
     * output format and exists solely for human documentation.
     * </p>
     *
     * @param fqcn             fully qualified class name targeted by this entry
     * @param method           method name targeted; {@code null} for a class-level
     *                         override that applies to all methods in the class
     * @param securityRelevant override value for security relevance; {@code null}
     *                         means "keep existing"
     * @param tags             override value for taxonomy tags; {@code null} means
     *                         "keep existing"
     * @param displayName      override value for the suggested display name;
     *                         {@code null} means "keep existing"
     * @param reason           override value for the classification rationale;
     *                         {@code null} means "keep existing"
     * @param note             free-text annotation for human use; never emitted in
     *                         any output
     */
    public record Entry(String fqcn, String method, Boolean securityRelevant, List<String> tags,
            String displayName, String reason, String note) {
    }

    // -------------------------------------------------------------------------
    // YAML deserialization POJOs
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OverrideFile {

        @JsonProperty("overrides")
        /* default */ List<EntryDto> overrides;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class EntryDto {

        @JsonProperty("fqcn")
        /* default */ String fqcn;

        @JsonProperty("method")
        /* default */ String method;

        @JsonProperty("securityRelevant")
        /* default */ Boolean securityRelevant;

        @JsonProperty("tags")
        /* default */ List<String> tags;

        @JsonProperty("displayName")
        /* default */ String displayName;

        @JsonProperty("reason")
        /* default */ String reason;

        @JsonProperty("note")
        /* default */ String note;
    }
}
