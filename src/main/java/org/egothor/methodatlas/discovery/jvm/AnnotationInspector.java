package org.egothor.methodatlas.discovery.jvm;

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
 * @see JavaTestDiscovery
 */
public final class AnnotationInspector {

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
    public static final Set<String> DEFAULT_TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");

    /**
     * Annotation simple names recognised as JUnit 4 test methods.
     *
     * <p>
     * Includes {@code Test} (shared with JUnit 5 and TestNG) and
     * {@code Theory} from {@code org.junit.experimental.theories}.
     * </p>
     */
    public static final Set<String> JUNIT4_TEST_ANNOTATIONS = Set.of("Test", "Theory");

    /**
     * Annotation simple names recognised as TestNG test methods.
     *
     * <p>
     * TestNG uses a single {@code @Test} annotation for all test methods,
     * including data-driven variants (those specify {@code dataProvider} as
     * an attribute rather than using a separate annotation).
     * </p>
     */
    public static final Set<String> TESTNG_TEST_ANNOTATIONS = Set.of("Test");

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
    public static Set<String> effectiveAnnotations(CompilationUnit cu, Set<String> configured) {
        if (!DEFAULT_TEST_ANNOTATIONS.equals(configured)) {
            return configured;
        }

        boolean hasJUnit4 = false;
        boolean hasTestNG = false;
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (name.startsWith("org.junit.jupiter")) { // NOPMD EmptyControlStatement — JUnit 5 is the default; no action needed
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
    public static boolean isJUnitTest(MethodDeclaration method) {
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
    public static boolean isJUnitTest(MethodDeclaration method, Set<String> testAnnotations) {
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
     * Extracts all tag values declared on a method.
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
    public static List<String> getTagValues(MethodDeclaration method) {
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
     * Returns the string value of the {@code @DisplayName} annotation if present
     * on the method.
     *
     * <p>
     * Both the single-member form {@code @DisplayName("text")} and the normal
     * annotation form {@code @DisplayName(value = "text")} are supported.
     * Matching is performed against the simple annotation name {@code "DisplayName"}.
     * </p>
     *
     * <p>
     * The return value distinguishes three cases:
     * </p>
     * <ul>
     * <li>{@code null} — no {@code @DisplayName} annotation is present on the method</li>
     * <li>{@code ""} (empty string) — a {@code @DisplayName("")} annotation is present
     *     but its value is an empty string; this is a malformed annotation because JUnit
     *     requires a non-blank display name</li>
     * <li>any non-empty string — the annotation is present with a non-blank value</li>
     * </ul>
     *
     * @param method method declaration whose annotations should be inspected
     * @return the display name text value, {@code ""} when the annotation is
     *         present with an empty value, or {@code null} when no display name
     *         annotation is present
     */
    public static String getDisplayName(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            if (!"DisplayName".equals(annotation.getNameAsString())) {
                continue;
            }
            if (annotation.isSingleMemberAnnotationExpr()) {
                Expression memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();
                if (memberValue.isStringLiteralExpr()) {
                    return memberValue.asStringLiteralExpr().asString();
                }
            } else if (annotation.isNormalAnnotationExpr()) {
                for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                    if ("value".equals(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr()) {
                        return pair.getValue().asStringLiteralExpr().asString();
                    }
                }
            }
        }
        return null;
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
    public static int countLOC(MethodDeclaration method) {
        return method.getRange().map(range -> range.end.line - range.begin.line + 1).orElse(0);
    }

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
