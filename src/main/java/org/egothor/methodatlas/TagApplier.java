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
    static final String IMPORT_DISPLAY_NAME = "org.junit.jupiter.api.DisplayName";

    /** Fully qualified name of {@code @Tag} for import management. */
    static final String IMPORT_TAG = "org.junit.jupiter.api.Tag";

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
    record ClassResult(int annotationsAdded, int displayNamesAdded, int tagsAdded) {

        /**
         * Returns {@code true} when at least one annotation was inserted.
         *
         * @return {@code true} if the class was modified
         */
        boolean modified() {
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
    static ClassResult applyToClass(ClassOrInterfaceDeclaration clazz, SuggestionLookup lookup,
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
