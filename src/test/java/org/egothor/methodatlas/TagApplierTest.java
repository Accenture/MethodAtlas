package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.SuggestionLookup;
import org.junit.jupiter.api.Test;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

class TagApplierTest {

    private static final Set<String> TEST_ANNOTATIONS = AnnotationInspector.DEFAULT_TEST_ANNOTATIONS;

    private static CompilationUnit parse(String source) {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(LanguageLevel.JAVA_21);
        return new JavaParser(cfg).parse(source).getResult().orElseThrow();
    }

    private static ClassOrInterfaceDeclaration firstClass(CompilationUnit cu) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).get(0);
    }

    private static SuggestionLookup lookupWith(AiMethodSuggestion... suggestions) {
        AiClassSuggestion cls = new AiClassSuggestion(null, null, null, null, List.of(suggestions));
        return SuggestionLookup.from(cls);
    }

    private static AiMethodSuggestion securitySuggestion(String method, String displayName, List<String> tags) {
        return new AiMethodSuggestion(method, true, displayName, tags, null, 0.9);
    }

    private static AiMethodSuggestion nonSecuritySuggestion(String method) {
        return new AiMethodSuggestion(method, false, "irrelevant", List.of("auth"), null, 0.0);
    }

    // -------------------------------------------------------------------------
    // applyToClass – basic behaviour
    // -------------------------------------------------------------------------

    @Test
    void noAnnotationsAddedWhenSuggestionIsNull() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                }
                """);
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu),
                SuggestionLookup.from(null), TEST_ANNOTATIONS);
        assertFalse(result.modified());
        assertEquals(0, result.annotationsAdded());
    }

    @Test
    void noAnnotationsAddedWhenNotSecurityRelevant() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                }
                """);
        SuggestionLookup lookup = lookupWith(nonSecuritySuggestion("testFoo"));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);
        assertFalse(result.modified());
    }

    @Test
    void displayNameAddedForSecurityRelevantMethod() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                }
                """);
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("testFoo", "Verifies auth token is rejected", List.of("security")));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertTrue(result.modified());
        assertEquals(1, result.displayNamesAdded());
        assertEquals(1, result.tagsAdded());    // "security" tag

        String out = cu.toString();
        assertTrue(out.contains("@DisplayName(\"Verifies auth token is rejected\")"), out);
        assertTrue(out.contains("@Tag(\"security\")"), out);
    }

    @Test
    void displayNameNotAddedWhenAlreadyPresent() {
        CompilationUnit cu = parse("""
                import org.junit.jupiter.api.DisplayName;
                class FooTest {
                    @org.junit.jupiter.api.Test
                    @DisplayName("existing name")
                    void testFoo() {}
                }
                """);
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("testFoo", "New name", List.of("security")));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertEquals(0, result.displayNamesAdded());
        assertFalse(cu.toString().contains("New name"));
        assertTrue(cu.toString().contains("existing name"));
    }

    @Test
    void tagNotAddedWhenAlreadyPresent() {
        CompilationUnit cu = parse("""
                import org.junit.jupiter.api.Tag;
                class FooTest {
                    @org.junit.jupiter.api.Test
                    @Tag("security")
                    void testFoo() {}
                }
                """);
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("testFoo", null, List.of("security")));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertEquals(0, result.tagsAdded());
    }

    @Test
    void multipleTagsAdded() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                }
                """);
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("testFoo", null, List.of("security", "auth", "injection")));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertEquals(3, result.tagsAdded());
        String out = cu.toString();
        assertTrue(out.contains("@Tag(\"security\")"), out);
        assertTrue(out.contains("@Tag(\"auth\")"), out);
        assertTrue(out.contains("@Tag(\"injection\")"), out);
    }

    @Test
    void duplicateTagInSuggestionAddedOnlyOnce() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                }
                """);
        // AI returns "security" twice by mistake
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("testFoo", null, List.of("security", "security")));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertEquals(1, result.tagsAdded());
    }

    @Test
    void nullAndBlankTagsIgnored() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                }
                """);
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("testFoo", null, List.of("security", "", "  ")));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertEquals(1, result.tagsAdded()); // only "security" is valid
    }

    @Test
    void nullDisplayNameSkipped() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                }
                """);
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("testFoo", null, List.of()));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertEquals(0, result.displayNamesAdded());
    }

    @Test
    void blankDisplayNameSkipped() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                }
                """);
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("testFoo", "   ", List.of()));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertEquals(0, result.displayNamesAdded());
    }

    @Test
    void onlyTestMethodsAnnotated() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                    void helperMethod() {}
                }
                """);
        // suggestion for the helper (non-test) method
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("helperMethod", "Should not be added", List.of("security")));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertFalse(result.modified());
        assertFalse(cu.toString().contains("DisplayName"));
    }

    @Test
    void innerClassMethodsNotProcessedByOuterClassIteration() {
        CompilationUnit cu = parse("""
                class OuterTest {
                    @org.junit.jupiter.api.Test
                    void outerTest() {}
                    class InnerTest {
                        @org.junit.jupiter.api.Test
                        void innerTest() {}
                    }
                }
                """);
        // Only a suggestion for outerTest
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("outerTest", "Outer display name", List.of("security")));

        // Apply to the outer class (uses getMethods() — direct methods only)
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertEquals(1, result.displayNamesAdded());
        assertTrue(cu.toString().contains("Outer display name"));
    }

    // -------------------------------------------------------------------------
    // Import constants
    // -------------------------------------------------------------------------

    @Test
    void importConstantsAreCorrect() {
        assertEquals("org.junit.jupiter.api.DisplayName", TagApplier.IMPORT_DISPLAY_NAME);
        assertEquals("org.junit.jupiter.api.Tag", TagApplier.IMPORT_TAG);
    }

    // -------------------------------------------------------------------------
    // ClassResult helpers
    // -------------------------------------------------------------------------

    @Test
    void classResultModifiedFalseWhenZeroAdded() {
        TagApplier.ClassResult r = new TagApplier.ClassResult(0, 0, 0);
        assertFalse(r.modified());
    }

    @Test
    void classResultModifiedTrueWhenNonZero() {
        TagApplier.ClassResult r = new TagApplier.ClassResult(1, 0, 1);
        assertTrue(r.modified());
    }

    @Test
    void annotationsAddedEqualsSumOfDisplayNamesAndTags() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                }
                """);
        SuggestionLookup lookup = lookupWith(
                securitySuggestion("testFoo", "My name", List.of("security", "auth")));
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertEquals(result.displayNamesAdded() + result.tagsAdded(), result.annotationsAdded());
    }
}
