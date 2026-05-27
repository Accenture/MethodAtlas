package org.egothor.methodatlas.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable lookup structure providing efficient access to AI-generated method
 * suggestions by method name.
 *
 * <p>
 * This class acts as an adapter between the class-level suggestion model
 * returned by the AI subsystem ({@link AiClassSuggestion}) and the per-method
 * processing logic used by {@code MethodAtlasApp}. It converts the list of
 * {@link AiMethodSuggestion} objects into a name-indexed lookup map so that
 * suggestions can be retrieved in constant time during traversal of parsed test
 * methods.
 * </p>
 *
 * <h2>Design Characteristics</h2>
 *
 * <ul>
 * <li>immutable after construction</li>
 * <li>null-safe for missing or malformed AI responses</li>
 * <li>optimized for repeated method-level lookups</li>
 * </ul>
 *
 * <p>
 * If the AI response contains duplicate suggestions for the same method, only
 * the first occurrence is retained.
 * </p>
 *
 * @see AiClassSuggestion
 * @see AiMethodSuggestion
 */
public final class SuggestionLookup {

    private final Map<String, AiMethodSuggestion> byMethodName;

    /**
     * Creates a new immutable lookup instance backed by the supplied map.
     *
     * <p>
     * The internal map is defensively copied to guarantee immutability of the
     * lookup structure.
     * </p>
     *
     * @param byMethodName mapping from method names to AI suggestions
     */
    private SuggestionLookup(Map<String, AiMethodSuggestion> byMethodName) {
        this.byMethodName = Map.copyOf(byMethodName);
    }

    /**
     * Creates a lookup instance from a class-level AI suggestion result.
     *
     * <p>
     * The method extracts all method suggestions contained in the supplied
     * {@link AiClassSuggestion} and indexes them by method name. Entries with
     * {@code null} suggestions, missing method names, or blank method names are
     * ignored.
     * </p>
     *
     * <p>
     * If the suggestion contains no method entries, an empty lookup instance is
     * returned.
     * </p>
     *
     * @param suggestion AI classification result for a test class
     * @return lookup structure providing fast access to method suggestions
     */
    public static SuggestionLookup from(AiClassSuggestion suggestion) {
        if (suggestion == null || suggestion.methods() == null || suggestion.methods().isEmpty()) {
            return new SuggestionLookup(Map.of());
        }

        Map<String, AiMethodSuggestion> map = new HashMap<>();
        for (AiMethodSuggestion methodSuggestion : suggestion.methods()) {
            if (methodSuggestion == null) {
                continue;
            }
            if (methodSuggestion.methodName() == null || methodSuggestion.methodName().isBlank()) {
                continue;
            }
            map.putIfAbsent(methodSuggestion.methodName(), methodSuggestion);
        }

        return new SuggestionLookup(map);
    }

    /**
     * Retrieves the AI suggestion for the specified method name.
     *
     * <p>
     * If no suggestion exists for the method, an empty {@link Optional} is
     * returned.
     * </p>
     *
     * @param methodName name of the method being queried
     * @return optional containing the suggestion if present
     *
     * @throws NullPointerException if {@code methodName} is {@code null}
     */
    public Optional<AiMethodSuggestion> find(String methodName) {
        Objects.requireNonNull(methodName, "methodName");
        return Optional.ofNullable(byMethodName.get(methodName));
    }
}