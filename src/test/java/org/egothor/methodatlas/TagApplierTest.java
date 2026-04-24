package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.SuggestionLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

/**
 * Unit tests for {@link TagApplier}.
 *
 * <p>
 * This class verifies that AI suggestions are correctly applied as
 * {@code @DisplayName} and {@code @Tag} annotations to JUnit test methods in
 * parsed Java source trees. Edge cases include null suggestions, non-security
 * methods, duplicate tags, blank display names, inner classes, and null tag
 * lists.
 * </p>
 */
@Tag("unit")
@Tag("tag-applier")
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
        return new AiMethodSuggestion(method, true, displayName, tags, null, 0.9, 0.0);
    }

    private static AiMethodSuggestion nonSecuritySuggestion(String method) {
        return new AiMethodSuggestion(method, false, "irrelevant", List.of("auth"), null, 0.0, 0.0);
    }

    // -------------------------------------------------------------------------
    // applyToClass – basic behaviour
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("no annotations added when suggestion lookup is null (class-level null suggestion)")
    @Tag("positive")
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
    @DisplayName("no annotations added when suggestion marks method as not security relevant")
    @Tag("positive")
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
    @DisplayName("@DisplayName and @Tag added for security-relevant method suggestion")
    @Tag("positive")
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
    @DisplayName("@DisplayName not added when method already has @DisplayName annotation")
    @Tag("edge-case")
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
    @DisplayName("@Tag not added when the tag value already exists on the method")
    @Tag("edge-case")
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
    @DisplayName("multiple tags from suggestion are all added to the method")
    @Tag("positive")
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
    @DisplayName("duplicate tag in suggestion list is added only once")
    @Tag("edge-case")
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
    @DisplayName("null and blank tag values in suggestion are ignored")
    @Tag("edge-case")
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
    @DisplayName("null displayName in suggestion is skipped without adding @DisplayName")
    @Tag("edge-case")
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
    @DisplayName("blank displayName in suggestion is skipped without adding @DisplayName")
    @Tag("edge-case")
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
    @DisplayName("suggestion for non-test helper method does not add any annotations")
    @Tag("negative")
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
    @DisplayName("inner class methods are not processed when applying to outer class")
    @Tag("edge-case")
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
    @DisplayName("IMPORT_DISPLAY_NAME and IMPORT_TAG constants have correct fully-qualified values")
    @Tag("positive")
    void importConstantsAreCorrect() {
        assertEquals("org.junit.jupiter.api.DisplayName", TagApplier.IMPORT_DISPLAY_NAME);
        assertEquals("org.junit.jupiter.api.Tag", TagApplier.IMPORT_TAG);
    }

    // -------------------------------------------------------------------------
    // ClassResult helpers
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ClassResult.modified() returns false when annotationsAdded is zero")
    @Tag("positive")
    void classResultModifiedFalseWhenZeroAdded() {
        TagApplier.ClassResult r = new TagApplier.ClassResult(0, 0, 0);
        assertFalse(r.modified());
    }

    @Test
    @DisplayName("ClassResult.modified() returns true when annotationsAdded is non-zero")
    @Tag("positive")
    void classResultModifiedTrueWhenNonZero() {
        TagApplier.ClassResult r = new TagApplier.ClassResult(1, 0, 1);
        assertTrue(r.modified());
    }

    @Test
    @DisplayName("annotationsAdded equals the sum of displayNamesAdded and tagsAdded")
    @Tag("positive")
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

    // -------------------------------------------------------------------------
    // New edge case: null tags list in AiMethodSuggestion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null tags list in AiMethodSuggestion adds no @Tag annotations")
    @Tag("edge-case")
    void nullTagListInSuggestion_addsNoTags() {
        CompilationUnit cu = parse("""
                class FooTest {
                    @org.junit.jupiter.api.Test
                    void testFoo() {}
                }
                """);
        // Create suggestion with null tags (not empty list, but null)
        AiMethodSuggestion suggestion = new AiMethodSuggestion("testFoo", true, null, null, null, 1.0, 0.0);
        SuggestionLookup lookup = lookupWith(suggestion);
        TagApplier.ClassResult result = TagApplier.applyToClass(firstClass(cu), lookup, TEST_ANNOTATIONS);

        assertEquals(0, result.tagsAdded());
    }
}
