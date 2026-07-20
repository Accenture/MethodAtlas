package org.egothor.methodatlas.gui.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.egothor.methodatlas.ai.AiMethodSuggestion;

/**
 * Swing-free tag-staging computations shared with the tag editor UI.
 *
 * <p>
 * The tag editor lets a reviewer stage a set of {@code @Tag} values (and an
 * optional display name) for a method before writing them to source.  The pure
 * "which tags result" logic is captured here so it can be unit-tested without a
 * Swing event loop; the panel supplies the user-interface state (toggled chips,
 * override text) and applies the result via
 * {@link MethodEntry#setStagedPatch(List, String)}.
 * </p>
 */
public final class TagStaging {

    private TagStaging() {
        // utility class — no instances
    }

    /**
     * Returns {@code base} followed by {@code extras}, de-duplicated while
     * preserving first-seen order.
     *
     * @param base   the base tag list (existing source tags); must not be {@code null}
     * @param extras additional tags to append; must not be {@code null}
     * @return an order-preserving, de-duplicated union; never {@code null}
     */
    public static List<String> merge(List<String> base, Collection<String> extras) {
        Set<String> tags = new LinkedHashSet<>(base);
        tags.addAll(extras);
        return new ArrayList<>(tags);
    }

    /**
     * Parses a comma-separated custom-override string into trimmed, non-empty
     * tag tokens.
     *
     * @param override raw override text; may be {@code null} or blank
     * @return list of trimmed, non-empty tags in input order; never {@code null}
     */
    public static List<String> parseOverride(String override) {
        List<String> result = new ArrayList<>();
        if (override == null) {
            return result;
        }
        String trimmed = override.trim();
        if (trimmed.isEmpty()) {
            return result;
        }
        for (String token : trimmed.split(",", -1)) {
            String tag = token.trim();
            if (!tag.isEmpty()) {
                result.add(tag);
            }
        }
        return result;
    }

    /**
     * Computes the tag list to stage when the reviewer accepts every
     * AI-suggested tag: the method's existing source tags unioned with all AI
     * tags.
     *
     * @param entry the method entry to stage for; must not be {@code null}
     * @return tags to stage; never {@code null}
     */
    public static List<String> acceptAllAiTags(MethodEntry entry) {
        AiMethodSuggestion suggestion = entry.suggestion();
        List<String> aiTags = (suggestion != null && suggestion.tags() != null)
                ? suggestion.tags() : List.of();
        return merge(entry.discovered().tags(), aiTags);
    }

    /**
     * Computes the tag list to stage from an explicit selection of AI tags plus
     * a custom-override string: existing source tags, then the selected AI
     * tags, then the parsed override tokens.
     *
     * @param entry          the method entry to stage for; must not be {@code null}
     * @param selectedAiTags AI tags the reviewer toggled on; must not be {@code null}
     * @param override       raw custom-override text; may be {@code null} or blank
     * @return tags to stage; never {@code null}
     */
    public static List<String> selectedTags(MethodEntry entry,
            Collection<String> selectedAiTags, String override) {
        List<String> withSelected = merge(entry.discovered().tags(), selectedAiTags);
        return merge(withSelected, parseOverride(override));
    }

    /**
     * Returns the AI-suggested display name to stage alongside the tags, or
     * {@code null} when one is not available or the source already declares a
     * display name.
     *
     * @param entry the method entry to stage for; must not be {@code null}
     * @return display name to stage, or {@code null}
     */
    public static String resolveDisplayName(MethodEntry entry) {
        String dn = entry.suggestedDisplayName();
        if (dn != null && !dn.isBlank() && entry.discovered().displayName() == null) {
            return dn;
        }
        return null;
    }
}
