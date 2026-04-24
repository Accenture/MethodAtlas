package org.egothor.methodatlas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;

/**
 * Static utilities for inspecting test-framework annotations on parsed method
 * declarations.
 *
 * <p>
 * This class centralizes the annotation-analysis logic used during test method
 * discovery. All methods operate on parsed AST nodes produced by JavaParser and
 * do not perform symbol resolution.
 * </p>
 *
 * <p>
 * Annotation matching is performed by simple name because fully qualified
 * names require symbol resolution, which is not available in the current
 * source-only parsing configuration. False positives are therefore possible if
 * a project defines a custom annotation with the same simple name as a supported
 * test annotation.
 * </p>
 *
 * <p>
 * {@link #effectiveAnnotations(CompilationUnit, Set)} performs per-file
 * framework detection from import declarations and selects the appropriate
 * annotation set automatically when no custom set has been configured.
 * </p>
 *
 * <p>
 * This class is a non-instantiable utility holder.
 * </p>
 *
 * @see MethodAtlasApp
 */
final class AnnotationInspector {

    /**
     * Default annotation simple names recognised as JUnit 5 (Jupiter) test
     * methods when no custom set is configured and no other framework is
     * detected from import declarations.
     *
     * <p>
     * The set contains: {@code Test}, {@code ParameterizedTest},
     * {@code RepeatedTest}, {@code TestFactory}, and {@code TestTemplate}.
     * </p>
     */
    /* default */ static final Set<String> DEFAULT_TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");

    /**
     * Annotation simple names recognised as JUnit 4 test methods.
     *
     * <p>
     * Includes {@code Test} (shared with JUnit 5 and TestNG) and
     * {@code Theory} from {@code org.junit.experimental.theories}.
     * </p>
     */
    /* default */ static final Set<String> JUNIT4_TEST_ANNOTATIONS = Set.of("Test", "Theory");

    /**
     * Annotation simple names recognised as TestNG test methods.
     *
     * <p>
     * TestNG uses a single {@code @Test} annotation for all test methods,
     * including data-driven variants (those specify {@code dataProvider} as
     * an attribute rather than using a separate annotation).
     * </p>
     */
    /* default */ static final Set<String> TESTNG_TEST_ANNOTATIONS = Set.of("Test");

    /**
     * Prevents instantiation of this utility class.
     */
    private AnnotationInspector() {
    }

    /**
     * Returns the effective annotation set to use for test method discovery in
     * a given compilation unit.
     *
     * <p>
     * When {@code configured} is the {@link #DEFAULT_TEST_ANNOTATIONS default set}
     * (i.e. the user did not supply a custom {@code -test-annotation} flag or
     * {@code testAnnotations} YAML entry), the method inspects the compilation
     * unit's import declarations to detect the test framework and returns the
     * framework-appropriate annotation set:
     * </p>
     *
     * <ul>
     *   <li>{@code org.junit.jupiter.*} imports → JUnit 5 ({@link #DEFAULT_TEST_ANNOTATIONS})</li>
     *   <li>{@code org.junit.*} or {@code junit.framework.*} imports → JUnit 4
     *       ({@link #JUNIT4_TEST_ANNOTATIONS}, adds {@code Theory})</li>
     *   <li>{@code org.testng.*} imports → TestNG ({@link #TESTNG_TEST_ANNOTATIONS})</li>
     * </ul>
     *
     * <p>
     * If multiple frameworks are imported (e.g. during a JUnit 4 → 5 migration),
     * the union of the matching annotation sets is returned. When no framework
     * imports are found, {@link #DEFAULT_TEST_ANNOTATIONS} is used as fallback.
     * </p>
     *
     * <p>
     * When {@code configured} differs from the default set, the caller has
     * explicitly customised the annotation list; auto-detection is skipped and
     * {@code configured} is returned unchanged.
     * </p>
     *
     * @param cu         parsed compilation unit whose imports are inspected
     * @param configured the annotation set from configuration; if equal to
     *                   {@link #DEFAULT_TEST_ANNOTATIONS}, auto-detection runs
     * @return effective annotation set for the given file; never {@code null}
     */
    /* default */ static Set<String> effectiveAnnotations(CompilationUnit cu, Set<String> configured) {
        if (!DEFAULT_TEST_ANNOTATIONS.equals(configured)) {
            return configured;
        }

        boolean hasJUnit4 = false;
        boolean hasTestNG = false;
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (name.startsWith("org.junit.jupiter")) {
                // JUnit 5 detected — defaults already cover this
            } else if (name.startsWith("org.junit") || name.startsWith("junit.framework")) {
                hasJUnit4 = true;
            } else if (name.startsWith("org.testng")) {
                hasTestNG = true;
            }
        }

        if (!hasJUnit4 && !hasTestNG) {
            return DEFAULT_TEST_ANNOTATIONS;
        }

        Set<String> effective = new LinkedHashSet<>(DEFAULT_TEST_ANNOTATIONS);
        if (hasJUnit4) {
            effective.addAll(JUNIT4_TEST_ANNOTATIONS);
        }
        if (hasTestNG) {
            effective.addAll(TESTNG_TEST_ANNOTATIONS);
        }
        return Collections.unmodifiableSet(effective);
    }

    /**
     * Determines whether a method declaration represents a JUnit test method
     * using the {@link #DEFAULT_TEST_ANNOTATIONS default annotation set}.
     *
     * @param method method declaration to inspect
     * @return {@code true} if the method carries a recognised test annotation
     */
    /* default */ static boolean isJUnitTest(MethodDeclaration method) {
        return isJUnitTest(method, DEFAULT_TEST_ANNOTATIONS);
    }

    /**
     * Determines whether a method declaration represents a JUnit test method
     * using a caller-supplied set of annotation simple names.
     *
     * <p>
     * Matching is performed against the annotation's simple name only, because
     * fully qualified name resolution requires symbol resolution which is not
     * available in source-only parsing mode.
     * </p>
     *
     * @param method          method declaration to inspect
     * @param testAnnotations set of annotation simple names to recognise as
     *                        test methods; must not be {@code null}
     * @return {@code true} if the method carries at least one annotation whose
     *         simple name is in {@code testAnnotations}
     */
    /* default */ static boolean isJUnitTest(MethodDeclaration method, Set<String> testAnnotations) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            // Strip qualifier so @org.junit.jupiter.api.Test matches the simple name "Test"
            int dot = name.lastIndexOf('.');
            String simpleName = dot >= 0 ? name.substring(dot + 1) : name;
            if (testAnnotations.contains(simpleName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts all JUnit tag values declared on a method.
     *
     * <p>
     * Both direct {@code @Tag} annotations and the container-style
     * {@code @Tags} annotation are supported. Tags are returned in declaration
     * order.
     * </p>
     *
     * @param method method declaration whose annotations should be inspected
     * @return list of extracted tag values; possibly empty but never
     *         {@code null}
     */
    /* default */ static List<String> getTagValues(MethodDeclaration method) {
        List<String> tagValues = new ArrayList<>();

        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();

            if ("Tag".equals(name)) { // NOPMD
                extractTagValue(annotation).ifPresent(tagValues::add);
            } else if ("Tags".equals(name)) { // NOPMD
                extractTagsContainerValues(annotation, tagValues);
            }
        }

        return tagValues;
    }

    /**
     * Computes the inclusive line count of a method declaration from its
     * source range.
     *
     * <p>
     * Returns {@code 0} if no source position information is available.
     * </p>
     *
     * @param method method declaration whose size should be measured
     * @return inclusive line count, or {@code 0} if no range information is
     *         available
     */
    /* default */ static int countLOC(MethodDeclaration method) {
        return method.getRange().map(range -> range.end.line - range.begin.line + 1).orElse(0);
    }

    /**
     * Extracts tag values from a JUnit {@code @Tags} container annotation.
     *
     * @param annotation annotation expected to represent {@code @Tags}
     * @param tagValues  destination list to which extracted tag values are
     *                   appended
     */
    private static void extractTagsContainerValues(AnnotationExpr annotation, List<String> tagValues) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            Expression memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();
            extractTagsFromContainerValue(memberValue, tagValues);
            return;
        }

        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if ("value".equals(pair.getNameAsString())) { // NOPMD
                    extractTagsFromContainerValue(pair.getValue(), tagValues);
                }
            }
        }
    }

    /**
     * Extracts individual {@code @Tag} values from the value expression of a
     * {@code @Tags} container annotation.
     *
     * @param value     expression holding the container contents
     * @param tagValues destination list to which extracted tag values are
     *                  appended
     */
    private static void extractTagsFromContainerValue(Expression value, List<String> tagValues) {
        if (!value.isArrayInitializerExpr()) {
            return;
        }

        ArrayInitializerExpr array = value.asArrayInitializerExpr();
        for (Expression expression : array.getValues()) {
            if (expression.isAnnotationExpr()) {
                extractTagValue(expression.asAnnotationExpr()).ifPresent(tagValues::add);
            }
        }
    }

    /**
     * Extracts the value from a single JUnit {@code @Tag} annotation.
     *
     * <p>
     * Both the single-member form {@code @Tag("x")} and the normal form
     * {@code @Tag(value = "x")} are supported.
     * </p>
     *
     * @param annotation annotation expected to represent {@code @Tag}
     * @return extracted tag value, or {@link Optional#empty()} if the
     *         annotation is not a supported {@code @Tag} form
     */
    private static Optional<String> extractTagValue(AnnotationExpr annotation) {
        if (!"Tag".equals(annotation.getNameAsString())) {
            return Optional.empty();
        }

        if (annotation.isSingleMemberAnnotationExpr()) {
            Expression memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();
            if (memberValue.isStringLiteralExpr()) {
                return Optional.of(memberValue.asStringLiteralExpr().asString());
            }
            return Optional.empty();
        }

        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if ("value".equals(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr()) {
                    return Optional.of(pair.getValue().asStringLiteralExpr().asString());
                }
            }
        }

        return Optional.empty();
    }
}
