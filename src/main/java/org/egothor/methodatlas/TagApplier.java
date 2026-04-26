package org.egothor.methodatlas;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.SuggestionLookup;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.StringLiteralExpr;

/**
 * Applies AI-generated {@code @DisplayName} and {@code @Tag} annotations to
 * security-relevant JUnit test methods in a parsed class declaration.
 *
 * <p>
 * For each test method in the class whose AI suggestion marks it as
 * security-relevant, this class:
 * </p>
 * <ul>
 * <li>inserts {@code @DisplayName("<text>")} if the suggestion provides a
 * non-blank display name and the annotation is not already present</li>
 * <li>inserts {@code @Tag("<tag>")} for each security tag in the suggestion
 * that is not already declared on the method</li>
 * </ul>
 *
 * <p>
 * Caller is responsible for managing JUnit imports on the enclosing
 * {@link com.github.javaparser.ast.CompilationUnit} based on the
 * {@link ClassResult#displayNamesAdded()} and {@link ClassResult#tagsAdded()}
 * counts. The constants {@link #IMPORT_DISPLAY_NAME} and {@link #IMPORT_TAG}
 * provide the fully qualified import strings.
 * </p>
 *
 * <p>
 * Only direct methods of the supplied class declaration are processed; methods
 * belonging to inner classes are handled when those inner classes are processed
 * as separate entries in the caller's iteration.
 * </p>
 *
 * <p>
 * This class is a non-instantiable utility holder.
 * </p>
 *
 * @see MethodAtlasApp
 * @see AnnotationInspector
 */
final class TagApplier {

    /** Fully qualified name of {@code @DisplayName} for import management. */
    /* default */ static final String IMPORT_DISPLAY_NAME = "org.junit.jupiter.api.DisplayName";

    /** Fully qualified name of {@code @Tag} for import management. */
    /* default */ static final String IMPORT_TAG = "org.junit.jupiter.api.Tag";

    private static final String ANNOTATION_DISPLAY_NAME = "DisplayName";
    private static final String ANNOTATION_TAG = "Tag";

    /**
     * Prevents instantiation of this utility class.
     */
    private TagApplier() {
    }

    /**
     * Result of applying annotations to a single class declaration.
     *
     * @param annotationsAdded  total count of annotations inserted
     * @param displayNamesAdded number of {@code @DisplayName} annotations inserted
     * @param tagsAdded         number of {@code @Tag} annotations inserted
     */
    /* default */ record ClassResult(int annotationsAdded, int displayNamesAdded, int tagsAdded) {

        /**
         * Returns {@code true} when at least one annotation was inserted.
         *
         * @return {@code true} if the class was modified
         */
        /* default */ boolean modified() {
            return annotationsAdded > 0;
        }
    }

    /**
     * Applies security annotations to the direct test methods of {@code clazz}.
     *
     * <p>
     * Only methods that are directly declared in {@code clazz} (not in nested
     * inner classes) are considered. Methods in inner classes are expected to be
     * processed when those inner classes are encountered in the caller's
     * iteration.
     * </p>
     *
     * <p>
     * No imports are modified by this method; the caller should inspect
     * {@link ClassResult#displayNamesAdded()} and {@link ClassResult#tagsAdded()}
     * and add the corresponding imports to the enclosing compilation unit when
     * the counts are non-zero.
     * </p>
     *
     * @param clazz           class declaration whose methods should be annotated
     * @param lookup          AI suggestions indexed by method name
     * @param testAnnotations annotation simple names identifying test methods
     * @return result describing what was changed; never {@code null}
     */
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    /* default */ static ClassResult applyToClass(ClassOrInterfaceDeclaration clazz, SuggestionLookup lookup,
            Set<String> testAnnotations) {
        int displayNamesAdded = 0;
        int tagsAdded = 0;

        for (MethodDeclaration method : clazz.getMethods()) {
            if (!AnnotationInspector.isJUnitTest(method, testAnnotations)) {
                continue;
            }
            AiMethodSuggestion suggestion = lookup.find(method.getNameAsString()).orElse(null);
            if (suggestion == null || !suggestion.securityRelevant()) {
                continue;
            }

            // Add @DisplayName if the suggestion provides one and it is not yet present.
            if (suggestion.displayName() != null && !suggestion.displayName().isBlank()
                    && !hasAnnotation(method, ANNOTATION_DISPLAY_NAME)) {
                method.addSingleMemberAnnotation(ANNOTATION_DISPLAY_NAME,
                        new StringLiteralExpr(suggestion.displayName()));
                displayNamesAdded++;
            }

            // Add @Tag for each security tag not already on the method.
            Set<String> existingTags = new HashSet<>(AnnotationInspector.getTagValues(method));
            List<String> suggestionTags = suggestion.tags();
            if (suggestionTags != null) {
                for (String tag : suggestionTags) {
                    if (tag != null && !tag.isBlank() && existingTags.add(tag)) {
                        method.addSingleMemberAnnotation(ANNOTATION_TAG, new StringLiteralExpr(tag));
                        tagsAdded++;
                    }
                }
            }
        }

        return new ClassResult(displayNamesAdded + tagsAdded, displayNamesAdded, tagsAdded);
    }

    /**
     * Result of applying a desired annotation state to a single method declaration.
     *
     * @param tagsAdded          number of {@code @Tag} annotations added
     * @param tagsRemoved        number of {@code @Tag} annotations removed
     * @param displayNameChanged whether the {@code @DisplayName} annotation was
     *                           set or removed
     */
    /* default */ record MethodApplyResult(int tagsAdded, int tagsRemoved, boolean displayNameChanged) {

        /**
         * Returns {@code true} when at least one annotation was added, removed,
         * or changed.
         *
         * @return {@code true} if the method was modified
         */
        /* default */ boolean modified() {
            return tagsAdded > 0 || tagsRemoved > 0 || displayNameChanged;
        }

        /**
         * Returns {@code true} when a {@code @Tag} import may be required.
         *
         * @return {@code true} if tags were added
         */
        /* default */ boolean needsTagImport() { return tagsAdded > 0; }

        /**
         * Returns {@code true} when a {@code @DisplayName} import may be required.
         *
         * @return {@code true} if the display name was changed
         */
        /* default */ boolean needsDisplayNameImport() { return displayNameChanged; }
    }

    /**
     * Applies a desired annotation state to a single test method declaration.
     *
     * <p>All existing {@code @Tag} and {@code @Tags} annotations are removed and
     * replaced with exactly the tags from {@code desiredTags}. The
     * {@code @DisplayName} annotation is driven by {@code desiredDisplayName}
     * according to a three-way contract:</p>
     * <ul>
     *   <li>{@code null} — column was absent from the source CSV (old format):
     *       the existing {@code @DisplayName} annotation is left untouched</li>
     *   <li>{@code ""} — column was present but empty: any existing
     *       {@code @DisplayName} is removed</li>
     *   <li>non-empty text — the desired display name: any existing
     *       {@code @DisplayName} is replaced with the new value</li>
     * </ul>
     *
     * <p>Callers are responsible for adding or preserving JUnit imports on the
     * enclosing {@link com.github.javaparser.ast.CompilationUnit} based on the
     * returned result.</p>
     *
     * @param method             method declaration to modify
     * @param desiredTags        exact set of {@code @Tag} values to apply; {@code null}
     *                           is treated as an empty list (all tags removed)
     * @param desiredDisplayName desired {@code @DisplayName} text; {@code null} means
     *                           leave unchanged; {@code ""} means remove; non-empty
     *                           means set to this value
     * @return result describing what changed; never {@code null}
     */
    /* default */ static MethodApplyResult applyDesiredState(MethodDeclaration method,
            List<String> desiredTags, String desiredDisplayName) {
        // Handle @DisplayName
        // null  → column absent from CSV (old format): leave @DisplayName unchanged
        // ""    → column present but empty: remove @DisplayName
        // text  → set @DisplayName to the given text
        boolean displayNameChanged = false;
        if (desiredDisplayName != null && !desiredDisplayName.isEmpty()) {
            method.getAnnotations().removeIf(a -> ANNOTATION_DISPLAY_NAME.equals(a.getNameAsString()));
            method.addSingleMemberAnnotation(ANNOTATION_DISPLAY_NAME,
                    new StringLiteralExpr(desiredDisplayName));
            displayNameChanged = true;
        } else if (desiredDisplayName != null) {
            // desiredDisplayName is "" — remove any existing @DisplayName
            boolean hadDisplayName = method.getAnnotations().stream()
                    .anyMatch(a -> ANNOTATION_DISPLAY_NAME.equals(a.getNameAsString()));
            method.getAnnotations().removeIf(a -> ANNOTATION_DISPLAY_NAME.equals(a.getNameAsString()));
            if (hadDisplayName) {
                displayNameChanged = true;
            }
        }
        // else desiredDisplayName == null → no change to @DisplayName

        // Handle @Tag annotations
        Set<String> existingTags = new HashSet<>(AnnotationInspector.getTagValues(method));
        method.getAnnotations().removeIf(a -> ANNOTATION_TAG.equals(a.getNameAsString())
                || "Tags".equals(a.getNameAsString()));
        int tagsRemoved = existingTags.size();

        int tagsAdded = 0;
        if (desiredTags != null) {
            for (String tag : desiredTags) {
                if (tag != null && !tag.isBlank()) {
                    method.addSingleMemberAnnotation(ANNOTATION_TAG, new StringLiteralExpr(tag));
                    tagsAdded++;
                }
            }
        }

        return new MethodApplyResult(tagsAdded, tagsRemoved, displayNameChanged);
    }

    /**
     * Returns {@code true} if the method already carries an annotation with the
     * given simple name.
     *
     * @param method     method declaration to inspect
     * @param simpleName annotation simple name to look for (e.g.
     *                   {@code "DisplayName"})
     * @return {@code true} if a matching annotation is present
     */
    private static boolean hasAnnotation(MethodDeclaration method, String simpleName) {
        return method.getAnnotations().stream()
                .anyMatch(a -> simpleName.equals(a.getNameAsString()));
    }
}
