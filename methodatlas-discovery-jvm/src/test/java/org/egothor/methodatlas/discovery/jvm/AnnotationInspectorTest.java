package org.egothor.methodatlas.discovery.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * Unit tests for {@link AnnotationInspector}.
 *
 * <p>
 * This class verifies the annotation-inspection utilities:
 * {@code isJUnitTest}, {@code getTagValues}, and {@code countLOC}.
 * JavaParser is used to construct {@link MethodDeclaration} instances from
 * inline source strings so that no on-disk fixture files are required.
 * </p>
 */
@Tag("unit")
@Tag("annotation-inspector")
class AnnotationInspectorTest {

    // -------------------------------------------------------------------------
    // DEFAULT_TEST_ANNOTATIONS
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DEFAULT_TEST_ANNOTATIONS contains exactly the 5 standard JUnit 5 test annotation names")
    @Tag("positive")
    void defaultTestAnnotations_containsExactlyFiveNames() {
        Set<String> defaults = AnnotationInspector.DEFAULT_TEST_ANNOTATIONS;
        assertEquals(5, defaults.size());
        assertTrue(defaults.contains("Test"));
        assertTrue(defaults.contains("ParameterizedTest"));
        assertTrue(defaults.contains("RepeatedTest"));
        assertTrue(defaults.contains("TestFactory"));
        assertTrue(defaults.contains("TestTemplate"));
    }

    // -------------------------------------------------------------------------
    // isJUnitTest – default annotation set
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isJUnitTest returns true for @Test annotation")
    @Tag("positive")
    void isJUnitTest_withAtTest_returnsTrue() {
        MethodDeclaration method = firstMethod("class C { @Test void m() {} }");
        assertTrue(AnnotationInspector.isJUnitTest(method));
    }

    @Test
    @DisplayName("isJUnitTest returns true for @ParameterizedTest annotation")
    @Tag("positive")
    void isJUnitTest_withParameterizedTest_returnsTrue() {
        MethodDeclaration method = firstMethod("class C { @ParameterizedTest void m() {} }");
        assertTrue(AnnotationInspector.isJUnitTest(method));
    }

    @Test
    @DisplayName("isJUnitTest returns true for @RepeatedTest annotation")
    @Tag("positive")
    void isJUnitTest_withRepeatedTest_returnsTrue() {
        MethodDeclaration method = firstMethod("class C { @RepeatedTest(3) void m() {} }");
        assertTrue(AnnotationInspector.isJUnitTest(method));
    }

    @Test
    @DisplayName("isJUnitTest returns true for @TestFactory annotation")
    @Tag("positive")
    void isJUnitTest_withTestFactory_returnsTrue() {
        MethodDeclaration method = firstMethod("class C { @TestFactory void m() {} }");
        assertTrue(AnnotationInspector.isJUnitTest(method));
    }

    @Test
    @DisplayName("isJUnitTest returns true for @TestTemplate annotation")
    @Tag("positive")
    void isJUnitTest_withTestTemplate_returnsTrue() {
        MethodDeclaration method = firstMethod("class C { @TestTemplate void m() {} }");
        assertTrue(AnnotationInspector.isJUnitTest(method));
    }

    @Test
    @DisplayName("isJUnitTest strips dot prefix and matches fully-qualified @org.junit.jupiter.api.Test as 'Test'")
    @Tag("positive")
    void isJUnitTest_withFullyQualifiedTestAnnotation_returnsTrue() {
        MethodDeclaration method = firstMethod("class C { @org.junit.jupiter.api.Test void m() {} }");
        assertTrue(AnnotationInspector.isJUnitTest(method));
    }

    @Test
    @DisplayName("isJUnitTest returns false when method has no annotations")
    @Tag("negative")
    void isJUnitTest_withNoAnnotations_returnsFalse() {
        MethodDeclaration method = firstMethod("class C { void m() {} }");
        assertFalse(AnnotationInspector.isJUnitTest(method));
    }

    @Test
    @DisplayName("isJUnitTest returns false for @Override annotation")
    @Tag("negative")
    void isJUnitTest_withOverrideAnnotation_returnsFalse() {
        MethodDeclaration method = firstMethod("class C { @Override void m() {} }");
        assertFalse(AnnotationInspector.isJUnitTest(method));
    }

    @Test
    @DisplayName("isJUnitTest returns false for @BeforeEach annotation")
    @Tag("negative")
    void isJUnitTest_withBeforeEachAnnotation_returnsFalse() {
        MethodDeclaration method = firstMethod("class C { @BeforeEach void m() {} }");
        assertFalse(AnnotationInspector.isJUnitTest(method));
    }

    // -------------------------------------------------------------------------
    // isJUnitTest – custom annotation set
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isJUnitTest with custom set returns true when method has matching custom annotation")
    @Tag("positive")
    void isJUnitTest_customSet_matchesCustomAnnotation() {
        MethodDeclaration method = firstMethod("class C { @MyTest void m() {} }");
        assertTrue(AnnotationInspector.isJUnitTest(method, Set.of("MyTest")));
    }

    @Test
    @DisplayName("isJUnitTest with custom set returns false when method has @Test but 'Test' not in set")
    @Tag("negative")
    void isJUnitTest_customSet_testNotInSet_returnsFalse() {
        MethodDeclaration method = firstMethod("class C { @Test void m() {} }");
        assertFalse(AnnotationInspector.isJUnitTest(method, Set.of("MyTest")));
    }

    @Test
    @DisplayName("isJUnitTest with empty custom set always returns false")
    @Tag("edge-case")
    void isJUnitTest_emptySet_alwaysReturnsFalse() {
        MethodDeclaration method = firstMethod("class C { @Test void m() {} }");
        assertFalse(AnnotationInspector.isJUnitTest(method, Set.of()));
    }

    @Test
    @DisplayName("isJUnitTest matches fully-qualified annotation against simple-name custom set")
    @Tag("positive")
    void isJUnitTest_qualifiedAnnotation_matchedAgainstSimpleNameCustomSet() {
        MethodDeclaration method = firstMethod("class C { @com.acme.MyTest void m() {} }");
        assertTrue(AnnotationInspector.isJUnitTest(method, Set.of("MyTest")));
    }

    // -------------------------------------------------------------------------
    // getTagValues
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getTagValues returns empty list when method has no @Tag annotations")
    @Tag("edge-case")
    void getTagValues_noTagAnnotation_returnsEmptyList() {
        MethodDeclaration method = firstMethod("class C { @Test void m() {} }");
        assertEquals(List.of(), AnnotationInspector.getTagValues(method));
    }

    @Test
    @DisplayName("getTagValues returns empty list when method has no annotations at all")
    @Tag("edge-case")
    void getTagValues_noAnnotationsAtAll_returnsEmptyList() {
        MethodDeclaration method = firstMethod("class C { void m() {} }");
        assertEquals(List.of(), AnnotationInspector.getTagValues(method));
    }

    @Test
    @DisplayName("getTagValues extracts value from single @Tag annotation")
    @Tag("positive")
    void getTagValues_singleTag_returnsValue() {
        MethodDeclaration method = firstMethod("class C { @Tag(\"fast\") void m() {} }");
        assertEquals(List.of("fast"), AnnotationInspector.getTagValues(method));
    }

    @Test
    @DisplayName("getTagValues extracts value from @Tag(value=\"x\") normal annotation form")
    @Tag("positive")
    void getTagValues_tagWithValueKey_returnsValue() {
        MethodDeclaration method = firstMethod("class C { @Tag(value=\"security\") void m() {} }");
        assertEquals(List.of("security"), AnnotationInspector.getTagValues(method));
    }

    @Test
    @DisplayName("getTagValues extracts all values from multiple @Tag annotations in declaration order")
    @Tag("positive")
    void getTagValues_multipleTags_returnsAllInOrder() {
        MethodDeclaration method = firstMethod("class C { @Tag(\"a\") @Tag(\"b\") void m() {} }");
        assertEquals(List.of("a", "b"), AnnotationInspector.getTagValues(method));
    }

    @Test
    @DisplayName("getTagValues extracts all values from @Tags container annotation")
    @Tag("positive")
    void getTagValues_tagsContainer_returnsAllValues() {
        MethodDeclaration method = firstMethod(
                "class C { @Tags({@Tag(\"a\"), @Tag(\"b\")}) void m() {} }");
        assertEquals(List.of("a", "b"), AnnotationInspector.getTagValues(method));
    }

    // -------------------------------------------------------------------------
    // countLOC
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("countLOC returns 1 for a single-line method")
    @Tag("positive")
    void countLOC_singleLineMethod_returnsOne() {
        MethodDeclaration method = firstMethod("class C { void m() {} }");
        assertEquals(1, AnnotationInspector.countLOC(method));
    }

    @Test
    @DisplayName("countLOC returns correct inclusive line count for a multi-line method")
    @Tag("positive")
    void countLOC_multiLineMethod_returnsCorrectCount() {
        // Source with explicit newlines so we can reason about line positions:
        // Line 1: class C {
        // Line 2:     void m() {
        // Line 3:         int x = 1;
        // Line 4:         int y = 2;
        // Line 5:         int z = x + y;
        // Line 6:     }
        // Line 7: }
        // Method spans lines 2-6 → LOC = 5
        String source = "class C {\n"
                + "    void m() {\n"
                + "        int x = 1;\n"
                + "        int y = 2;\n"
                + "        int z = x + y;\n"
                + "    }\n"
                + "}\n";
        MethodDeclaration method = firstMethod(source);
        assertEquals(5, AnnotationInspector.countLOC(method));
    }

    // -------------------------------------------------------------------------
    // effectiveAnnotations — framework auto-detection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("effectiveAnnotations returns defaults when no test-framework imports are present")
    @Tag("positive")
    void effectiveAnnotations_noFrameworkImports_returnsDefaults() {
        CompilationUnit cu = parse("class C {}");
        assertEquals(AnnotationInspector.DEFAULT_TEST_ANNOTATIONS,
                AnnotationInspector.effectiveAnnotations(cu, AnnotationInspector.DEFAULT_TEST_ANNOTATIONS));
    }

    @Test
    @DisplayName("effectiveAnnotations returns defaults for JUnit 5 imports")
    @Tag("positive")
    void effectiveAnnotations_junit5Imports_returnsDefaults() {
        CompilationUnit cu = parse("import org.junit.jupiter.api.Test; class C {}");
        assertEquals(AnnotationInspector.DEFAULT_TEST_ANNOTATIONS,
                AnnotationInspector.effectiveAnnotations(cu, AnnotationInspector.DEFAULT_TEST_ANNOTATIONS));
    }

    @Test
    @DisplayName("effectiveAnnotations includes Theory for JUnit 4 imports")
    @Tag("positive")
    void effectiveAnnotations_junit4Imports_includesTheory() {
        CompilationUnit cu = parse("import org.junit.Test; class C {}");
        Set<String> effective = AnnotationInspector.effectiveAnnotations(cu,
                AnnotationInspector.DEFAULT_TEST_ANNOTATIONS);
        assertTrue(effective.contains("Theory"), "JUnit 4 detection must add Theory");
        assertTrue(effective.contains("Test"), "Test must remain in effective set");
    }

    @Test
    @DisplayName("effectiveAnnotations includes Theory for junit.framework imports")
    @Tag("positive")
    void effectiveAnnotations_junitFrameworkImports_includesTheory() {
        CompilationUnit cu = parse("import junit.framework.TestCase; class C {}");
        Set<String> effective = AnnotationInspector.effectiveAnnotations(cu,
                AnnotationInspector.DEFAULT_TEST_ANNOTATIONS);
        assertTrue(effective.contains("Theory"));
    }

    @Test
    @DisplayName("effectiveAnnotations returns Test-only set for TestNG imports")
    @Tag("positive")
    void effectiveAnnotations_testNgImports_containsTest() {
        CompilationUnit cu = parse("import org.testng.annotations.Test; class C {}");
        Set<String> effective = AnnotationInspector.effectiveAnnotations(cu,
                AnnotationInspector.DEFAULT_TEST_ANNOTATIONS);
        assertTrue(effective.contains("Test"));
    }

    @Test
    @DisplayName("effectiveAnnotations returns union when both JUnit 4 and JUnit 5 imports are present")
    @Tag("positive")
    void effectiveAnnotations_mixedJunit4AndJunit5_returnsUnion() {
        CompilationUnit cu = parse(
                "import org.junit.Test; import org.junit.jupiter.api.ParameterizedTest; class C {}");
        Set<String> effective = AnnotationInspector.effectiveAnnotations(cu,
                AnnotationInspector.DEFAULT_TEST_ANNOTATIONS);
        assertTrue(effective.contains("Test"));
        assertTrue(effective.contains("Theory"), "Union must include JUnit 4's Theory");
        assertTrue(effective.contains("ParameterizedTest"), "Union must include JUnit 5's ParameterizedTest");
    }

    @Test
    @DisplayName("effectiveAnnotations returns custom set unchanged without auto-detection")
    @Tag("positive")
    void effectiveAnnotations_customConfigured_returnsCustomWithoutDetection() {
        CompilationUnit cu = parse("import org.junit.Test; class C {}");
        Set<String> custom = Set.of("MyTest");
        assertFalse(AnnotationInspector.effectiveAnnotations(cu, custom).contains("Theory"),
                "Custom annotation set must be returned as-is, Theory must not be added");
        assertEquals(custom, AnnotationInspector.effectiveAnnotations(cu, custom));
    }

    @Test
    @DisplayName("JUnit 4 @Theory method is detected via auto-detection from imports")
    @Tag("positive")
    void effectiveAnnotations_junit4TheoryMethod_isDetected() {
        CompilationUnit cu = parse(
                "import org.junit.Test;\n"
                + "import org.junit.experimental.theories.Theory;\n"
                + "class AuthTest {\n"
                + "  @Test public void testLogin() {}\n"
                + "  @Theory public void loginWithAnyInput(String input) {}\n"
                + "}");
        Set<String> effective = AnnotationInspector.effectiveAnnotations(cu,
                AnnotationInspector.DEFAULT_TEST_ANNOTATIONS);
        long count = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> AnnotationInspector.isJUnitTest(m, effective))
                .count();
        assertEquals(2, count, "Both @Test and @Theory methods must be detected");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static CompilationUnit parse(String source) {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(LanguageLevel.JAVA_21);
        return new JavaParser(cfg).parse(source).getResult().orElseThrow();
    }

    private static MethodDeclaration firstMethod(String source) {
        return parse(source).findAll(MethodDeclaration.class).get(0);
    }
}
