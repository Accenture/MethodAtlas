package org.egothor.methodatlas.gui.model;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.api.DiscoveredMethod;

import java.util.List;
import java.util.Objects;

/**
 * Mutable view-model for a single discovered test method.
 *
 * <p>The {@link #discovered} field is set once on creation; the
 * {@link #suggestion} field is filled in later by the AI enrichment
 * phase and the {@link #appliedTags} field is updated when the user
 * applies a patch to the source file.</p>
 */
public final class MethodEntry {

    /** Visual state shown in the results tree. */
    public enum TagStatus {
        /** No AI result available. */
        NO_AI,
        /** AI classified this method as not security-relevant. */
        NOT_SECURITY,
        /** AI suggests tags not yet present in the source. */
        NEEDS_REVIEW,
        /** Source tags are consistent with the AI suggestion. */
        OK
    }

    private final DiscoveredMethod discovered;
    private AiMethodSuggestion suggestion;
    private List<String> appliedTags;

    /**
     * Creates a new entry from a just-discovered method.
     *
     * @param discovered non-null discovered method
     * @param suggestion AI suggestion, may be {@code null}
     */
    public MethodEntry(DiscoveredMethod discovered, AiMethodSuggestion suggestion) {
        this.discovered = Objects.requireNonNull(discovered, "discovered");
        this.suggestion = suggestion;
    }

    /** @return the immutable discovery record */
    public DiscoveredMethod discovered() { return discovered; }

    /** @return AI suggestion, or {@code null} if not yet available */
    public AiMethodSuggestion suggestion() { return suggestion; }

    /** Updates the AI suggestion (called from the AI enrichment phase). */
    public void setSuggestion(AiMethodSuggestion suggestion) {
        this.suggestion = suggestion;
    }

    /**
     * Returns the tags that were last applied to the source file by this GUI,
     * or {@code null} when no patch has been applied in this session.
     *
     * @return applied tags, or {@code null}
     */
    public List<String> appliedTags() { return appliedTags; }

    /** Records the tags that were written to the source file. */
    public void setAppliedTags(List<String> tags) {
        this.appliedTags = tags == null ? null : List.copyOf(tags);
    }

    /**
     * Computes the visual status for the results tree.
     *
     * <p>Priority: if tags were applied in this session, compare against
     * the applied tags; otherwise compare the source tags against the AI
     * suggestion.</p>
     *
     * @return current tag status
     */
    public TagStatus tagStatus() {
        if (suggestion == null) {
            return TagStatus.NO_AI;
        }
        if (!suggestion.securityRelevant()) {
            return TagStatus.NOT_SECURITY;
        }
        List<String> aiTags = suggestion.tags() != null ? suggestion.tags() : List.of();
        if (aiTags.isEmpty()) {
            return TagStatus.OK;
        }
        List<String> reference = appliedTags != null ? appliedTags : discovered.tags();
        for (String aiTag : aiTags) {
            if (!reference.contains(aiTag)) {
                return TagStatus.NEEDS_REVIEW;
            }
        }
        return TagStatus.OK;
    }

    /** @return suggested display name from AI, or {@code null} */
    public String suggestedDisplayName() {
        return suggestion != null ? suggestion.displayName() : null;
    }

    @Override
    public String toString() {
        return discovered.fqcn() + "#" + discovered.method();
    }
}
