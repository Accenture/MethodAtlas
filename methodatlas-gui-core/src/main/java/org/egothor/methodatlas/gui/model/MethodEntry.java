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
        OK,
        /**
         * Changes have been staged via the GUI but not yet written to disk.
         * The pending state takes priority over all other statuses until the
         * staged patch is either saved (→ {@link #OK}/{@link #NEEDS_REVIEW})
         * or cleared (→ previous status).
         */
        PENDING_SAVE
    }

    private final DiscoveredMethod discovered;
    private AiMethodSuggestion suggestion;
    private List<String> appliedTags;

    // ── Staged (pending) patch ────────────────────────────────────────────
    private List<String> pendingTags;
    private String pendingDisplayName;

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
     * Returns {@code true} when this entry has a staged (pending) patch that
     * has not yet been written to disk.
     *
     * @return {@code true} if a staged patch exists
     */
    public boolean hasPendingChanges() { return pendingTags != null; }

    /**
     * Returns the tags queued to be written by a future "Save All" operation,
     * or {@code null} if no patch is staged.
     *
     * @return pending tag list, or {@code null}
     */
    public List<String> getPendingTags() { return pendingTags; }

    /**
     * Returns the display name queued to be written by a future "Save All"
     * operation, or {@code null} if none is staged.
     *
     * @return pending display name, or {@code null}
     */
    public String getPendingDisplayName() { return pendingDisplayName; }

    /**
     * Stages a patch for this entry without writing to disk.
     *
     * <p>Calling this method causes {@link #tagStatus()} to return
     * {@link TagStatus#PENDING_SAVE} until the patch is either saved
     * ({@link #clearStagedPatch()} + {@link #setAppliedTags(List)}) or
     * discarded ({@link #clearStagedPatch()}).</p>
     *
     * @param tags        tag list to stage; must not be {@code null}
     * @param displayName display name to stage, or {@code null} to leave unchanged
     */
    public void setStagedPatch(List<String> tags, String displayName) {
        this.pendingTags = List.copyOf(tags);
        this.pendingDisplayName = displayName;
    }

    /**
     * Removes any staged patch without writing to disk.
     *
     * <p>After this call {@link #hasPendingChanges()} returns {@code false}.</p>
     */
    public void clearStagedPatch() {
        this.pendingTags = null;
        this.pendingDisplayName = null;
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
        if (pendingTags != null) {
            return TagStatus.PENDING_SAVE;
        }
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
