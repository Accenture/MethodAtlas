package org.egothor.methodatlas.discovery.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * Unit tests for {@link JavaSourcePatcher}.
 *
 * <p>
 * This class verifies the annotation-patching logic of
 * {@link JavaSourcePatcher}: the {@code supports()} and {@code configure()}
 * contract, the static {@link JavaSourcePatcher#applyDesiredState} helper,
 * and the end-to-end {@link JavaSourcePatcher#patch} method that reads and
 * rewrites source files on disk.
 * </p>
 */
@Tag("unit")
@Tag("source-patcher")
class JavaSourcePatcherTest {

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

    private static JavaSourcePatcher patcherWithDefaults() {
        JavaSourcePatcher patcher = new JavaSourcePatcher();
        patcher.configure(new TestDiscoveryConfig(List.of("Test.java"), Set.of(), Map.of()));
        return patcher;
    }

    // -------------------------------------------------------------------------
    // supports / configure
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("supports() returns true for file ending with 'Test.java' by default")
    @Tag("positive")
    void supports_defaultSuffix_matchesTestJava() {
        JavaSourcePatcher patcher = new JavaSourcePatcher();
        assertTrue(patcher.supports(Path.of("FooTest.java")));
    }

    @Test
    @DisplayName("supports() returns false for file not ending with 'Test.java' by default")
    @Tag("negative")
    void supports_defaultSuffix_doesNotMatchHelper() {
        JavaSourcePatcher patcher = new JavaSourcePatcher();
        assertFalse(patcher.supports(Path.of("FooHelper.java")));
    }

    @Test
    @DisplayName("supports() returns false for null-name path")
    @Tag("edge-case")
    void supports_nullNamePath_returnsFalse() {
        JavaSourcePatcher patcher = new JavaSourcePatcher();
        // Path.of("/") has null getFileName() on some platforms — use root path
        assertFalse(patcher.supports(Path.of("/")));
    }

    @Test
    @DisplayName("configure() updates accepted suffix so supports() matches custom suffix")
    @Tag("positive")
    void configure_customSuffix_supportsCustomSuffix() {
        JavaSourcePatcher patcher = new JavaSourcePatcher();
        patcher.configure(new TestDiscoveryConfig(List.of("Spec.java"), Set.of(), Map.of()));
        assertTrue(patcher.supports(Path.of("LoginSpec.java")));
        assertFalse(patcher.supports(Path.of("LoginTest.java")));
    }

    @Test
    @DisplayName("configure() with empty suffixes list falls back to default 'Test.java'")
    @Tag("edge-case")
    void configure_emptySuffixes_fallsBackToDefault() {
        JavaSourcePatcher patcher = new JavaSourcePatcher();
        patcher.configure(new TestDiscoveryConfig(List.of(), Set.of(), Map.of()));
        assertTrue(patcher.supports(Path.of("FooTest.java")));
    }

    // -------------------------------------------------------------------------
    // IMPORT constants
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IMPORT_DISPLAY_NAME and IMPORT_TAG constants have correct fully-qualified values")
    @Tag("positive")
    void importConstantsAreCorrect() {
        assertEquals("org.junit.jupiter.api.DisplayName", JavaSourcePatcher.IMPORT_DISPLAY_NAME);
        assertEquals("org.junit.jupiter.api.Tag", JavaSourcePatcher.IMPORT_TAG);
    }

    // -------------------------------------------------------------------------
    // applyDesiredState — @DisplayName handling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("applyDesiredState sets @DisplayName when desiredDisplayName is non-empty")
    @Tag("positive")
    void applyDesiredState_nonEmptyDisplayName_setsAnnotation() {
        MethodDeclaration method = firstMethod("class C { @Test void m() {} }");
        JavaSourcePatcher.MethodApplyResult result =
                JavaSourcePatcher.applyDesiredState(method, List.of(), "My display name");
        assertTrue(result.displayNameChanged());
        assertTrue(result.needsDisplayNameImport());
        assertTrue(method.toString().contains("DisplayName(\"My display name\")"));
    }

    @Test
    @DisplayName("applyDesiredState replaces existing @DisplayName with new value")
    @Tag("positive")
    void applyDesiredState_replacesExistingDisplayName() {
        MethodDeclaration method = firstMethod(
                "class C { @Test @DisplayName(\"old\") void m() {} }");
        JavaSourcePatcher.MethodApplyResult result =
                JavaSourcePatcher.applyDesiredState(method, List.of(), "new value");
        assertTrue(result.displayNameChanged());
        String out = method.toString();
        assertTrue(out.contains("\"new value\""), out);
        assertFalse(out.contains("\"old\""), out);
    }

    @Test
    @DisplayName("applyDesiredState removes @DisplayName when desiredDisplayName is empty string")
    @Tag("positive")
    void applyDesiredState_emptyDisplayName_removesAnnotation() {
        MethodDeclaration method = firstMethod(
                "class C { @Test @DisplayName(\"existing\") void m() {} }");
        JavaSourcePatcher.MethodApplyResult result =
                JavaSourcePatcher.applyDesiredState(method, List.of(), "");
        assertTrue(result.displayNameChanged());
        assertFalse(method.toString().contains("DisplayName"));
    }

    @Test
    @DisplayName("applyDesiredState with empty string and no existing @DisplayName does not change anything")
    @Tag("edge-case")
    void applyDesiredState_emptyDisplayNameNoExisting_noChange() {
        MethodDeclaration method = firstMethod("class C { @Test void m() {} }");
        JavaSourcePatcher.MethodApplyResult result =
                JavaSourcePatcher.applyDesiredState(method, List.of(), "");
        assertFalse(result.displayNameChanged());
    }

    @Test
    @DisplayName("applyDesiredState with null desiredDisplayName leaves existing @DisplayName unchanged")
    @Tag("positive")
    void applyDesiredState_nullDisplayName_leavesExistingUntouched() {
        MethodDeclaration method = firstMethod(
                "class C { @Test @DisplayName(\"keep me\") void m() {} }");
        JavaSourcePatcher.MethodApplyResult result =
                JavaSourcePatcher.applyDesiredState(method, List.of(), null);
        assertFalse(result.displayNameChanged());
        assertTrue(method.toString().contains("\"keep me\""));
    }

    // -------------------------------------------------------------------------
    // applyDesiredState — @Tag handling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("applyDesiredState adds @Tag annotations for desired tags on method with no tags")
    @Tag("positive")
    void applyDesiredState_addsDesiredTags() {
        MethodDeclaration method = firstMethod("class C { @Test void m() {} }");
        JavaSourcePatcher.MethodApplyResult result =
                JavaSourcePatcher.applyDesiredState(method, List.of("security", "auth"), null);
        assertEquals(2, result.tagsAdded());
        assertTrue(result.needsTagImport());
        String out = method.toString();
        assertTrue(out.contains("@Tag(\"security\")"), out);
        assertTrue(out.contains("@Tag(\"auth\")"), out);
    }

    @Test
    @DisplayName("applyDesiredState removes all existing @Tag annotations when desired set is empty")
    @Tag("positive")
    void applyDesiredState_emptyDesiredTags_removesExistingTags() {
        MethodDeclaration method = firstMethod(
                "class C { @Test @Tag(\"security\") void m() {} }");
        JavaSourcePatcher.MethodApplyResult result =
                JavaSourcePatcher.applyDesiredState(method, List.of(), null);
        assertEquals(1, result.tagsRemoved());
        assertEquals(0, result.tagsAdded());
        assertFalse(method.toString().contains("@Tag"));
    }

    @Test
    @DisplayName("applyDesiredState does not mutate tags when existing and desired sets are equal")
    @Tag("edge-case")
    void applyDesiredState_sameTagSets_noChange() {
        MethodDeclaration method = firstMethod(
                "class C { @Test @Tag(\"security\") void m() {} }");
        JavaSourcePatcher.MethodApplyResult result =
                JavaSourcePatcher.applyDesiredState(method, List.of("security"), null);
        assertEquals(0, result.tagsAdded());
        assertEquals(0, result.tagsRemoved());
        assertFalse(result.modified());
    }

    @Test
    @DisplayName("applyDesiredState with null desiredTags removes all existing tags")
    @Tag("positive")
    void applyDesiredState_nullDesiredTags_removesExistingTags() {
        MethodDeclaration method = firstMethod(
                "class C { @Test @Tag(\"x\") void m() {} }");
        JavaSourcePatcher.MethodApplyResult result =
                JavaSourcePatcher.applyDesiredState(method, null, null);
        assertEquals(1, result.tagsRemoved());
        assertEquals(0, result.tagsAdded());
    }

    @Test
    @DisplayName("applyDesiredState removes @Tags container annotation")
    @Tag("positive")
    void applyDesiredState_tagsContainer_removed() {
        MethodDeclaration method = firstMethod(
                "class C { @Test @Tags({@Tag(\"a\"), @Tag(\"b\")}) void m() {} }");
        JavaSourcePatcher.applyDesiredState(method, List.of("c"), null);
        assertFalse(method.toString().contains("@Tags"), method.toString());
        assertTrue(method.toString().contains("@Tag(\"c\")"), method.toString());
    }

    @Test
    @DisplayName("applyDesiredState ignores null and blank entries in desired tags list")
    @Tag("edge-case")
    void applyDesiredState_nullAndBlankTagsIgnored() {
        MethodDeclaration method = firstMethod("class C { @Test void m() {} }");
        JavaSourcePatcher.MethodApplyResult result =
                JavaSourcePatcher.applyDesiredState(method, List.of("security", "", "  "), null);
        assertEquals(1, result.tagsAdded()); // only "security" is valid
    }

    // -------------------------------------------------------------------------
    // MethodApplyResult
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("MethodApplyResult.modified() returns false when nothing changed")
    @Tag("positive")
    void methodApplyResult_modifiedFalseWhenNothingChanged() {
        JavaSourcePatcher.MethodApplyResult r = new JavaSourcePatcher.MethodApplyResult(0, 0, false, Set.of());
        assertFalse(r.modified());
    }

    @Test
    @DisplayName("MethodApplyResult.modified() returns true when tags were added")
    @Tag("positive")
    void methodApplyResult_modifiedTrueWhenTagsAdded() {
        JavaSourcePatcher.MethodApplyResult r = new JavaSourcePatcher.MethodApplyResult(1, 0, false, Set.of());
        assertTrue(r.modified());
    }

    @Test
    @DisplayName("MethodApplyResult.modified() returns true when displayNameChanged is true")
    @Tag("positive")
    void methodApplyResult_modifiedTrueWhenDisplayNameChanged() {
        JavaSourcePatcher.MethodApplyResult r = new JavaSourcePatcher.MethodApplyResult(0, 0, true, Set.of());
        assertTrue(r.modified());
    }

    @Test
    @DisplayName("MethodApplyResult.needsTagImport() returns true only when tags were added")
    @Tag("positive")
    void methodApplyResult_needsTagImportOnlyWhenAdded() {
        assertTrue(new JavaSourcePatcher.MethodApplyResult(1, 0, false, Set.of()).needsTagImport());
        assertFalse(new JavaSourcePatcher.MethodApplyResult(0, 1, false, Set.of()).needsTagImport());
    }

    @Test
    @DisplayName("MethodApplyResult.needsDisplayNameImport() returns true only when displayNameChanged")
    @Tag("positive")
    void methodApplyResult_needsDisplayNameImportOnlyWhenChanged() {
        assertTrue(new JavaSourcePatcher.MethodApplyResult(0, 0, true, Set.of()).needsDisplayNameImport());
        assertFalse(new JavaSourcePatcher.MethodApplyResult(1, 0, false, Set.of()).needsDisplayNameImport());
    }

    // -------------------------------------------------------------------------
    // discoverMethodsByClass()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("discoverMethodsByClass() returns test methods grouped by FQCN")
    @Tag("positive")
    void discoverMethodsByClass_returnsTestMethods(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("LoginTest.java");
        Files.writeString(file, """
                package com.example;
                import org.junit.jupiter.api.Test;
                class LoginTest {
                    @Test void testLogin() {}
                    @Test void testLogout() {}
                    void helperMethod() {}
                }
                """, StandardCharsets.UTF_8);

        Map<String, List<String>> result = patcherWithDefaults().discoverMethodsByClass(file);

        assertEquals(1, result.size(), "Expected one FQCN entry");
        List<String> methods = result.get("com.example.LoginTest");
        assertTrue(methods != null, "Expected entry for com.example.LoginTest");
        assertTrue(methods.contains("testLogin"), "Expected testLogin");
        assertTrue(methods.contains("testLogout"), "Expected testLogout");
        assertFalse(methods.contains("helperMethod"), "helperMethod is not a test");
    }

    @Test
    @DisplayName("discoverMethodsByClass() returns empty map for file with no test methods")
    @Tag("edge-case")
    void discoverMethodsByClass_noTestMethods_returnsEmpty(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("HelperTest.java");
        Files.writeString(file, """
                package com.example;
                class HelperTest {
                    void helper() {}
                }
                """, StandardCharsets.UTF_8);

        Map<String, List<String>> result = patcherWithDefaults().discoverMethodsByClass(file);
        assertTrue(result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // patch() — end-to-end file patching
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("patch() adds @Tag to test method and writes file back")
    @Tag("positive")
    void patch_addTag_writesFileBack(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("FooTest.java");
        Files.writeString(file, """
                package com.example;
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test
                    void testFoo() {}
                }
                """, StandardCharsets.UTF_8);

        StringWriter sw = new StringWriter();
        patcherWithDefaults().patch(file,
                Map.of("testFoo", List.of("security")),
                Map.of(),
                new PrintWriter(sw));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("@Tag(\"security\")"), written);
        assertTrue(sw.toString().contains("Patched:"), sw.toString());
    }

    @Test
    @DisplayName("patch() adds @DisplayName to test method and writes file back")
    @Tag("positive")
    void patch_addDisplayName_writesFileBack(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("FooTest.java");
        Files.writeString(file, """
                package com.example;
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test
                    void testFoo() {}
                }
                """, StandardCharsets.UTF_8);

        StringWriter sw = new StringWriter();
        patcherWithDefaults().patch(file,
                Map.of(),
                Map.of("testFoo", "My display name"),
                new PrintWriter(sw));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("@DisplayName(\"My display name\")"), written);
    }

    @Test
    @DisplayName("patch() does not write file when no changes are made")
    @Tag("edge-case")
    void patch_noChanges_doesNotWriteFile(@TempDir Path tempDir) throws IOException {
        String original = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.Tag;
                class FooTest {
                    @Test
                    @Tag("security")
                    void testFoo() {}
                }
                """;
        Path file = tempDir.resolve("FooTest.java");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        StringWriter sw = new StringWriter();
        patcherWithDefaults().patch(file,
                Map.of("testFoo", List.of("security")),
                Map.of(),
                new PrintWriter(sw));

        // File should be unchanged (same content, no "Patched:" in diagnostics)
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(original, written);
        assertFalse(sw.toString().contains("Patched:"), sw.toString());
    }

    @Test
    @DisplayName("patch() skips methods not mentioned in either map")
    @Tag("edge-case")
    void patch_methodNotInMaps_isLeftUntouched(@TempDir Path tempDir) throws IOException {
        String original = """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.Tag;
                class FooTest {
                    @Test
                    @Tag("existing")
                    void testFoo() {}
                    @Test
                    void testBar() {}
                }
                """;
        Path file = tempDir.resolve("FooTest.java");
        Files.writeString(file, original, StandardCharsets.UTF_8);

        StringWriter sw = new StringWriter();
        patcherWithDefaults().patch(file,
                Map.of("testBar", List.of("new-tag")),
                Map.of(),
                new PrintWriter(sw));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        // testFoo still has its original tag
        assertTrue(written.contains("@Tag(\"existing\")"), written);
        // testBar got the new tag
        assertTrue(written.contains("@Tag(\"new-tag\")"), written);
    }

    @Test
    @DisplayName("patch() removes @Tag from test method when desired list is empty")
    @Tag("positive")
    void patch_emptyDesiredTags_removesExistingTag(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("FooTest.java");
        Files.writeString(file, """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.Tag;
                class FooTest {
                    @Test
                    @Tag("security")
                    void testFoo() {}
                }
                """, StandardCharsets.UTF_8);

        StringWriter sw = new StringWriter();
        patcherWithDefaults().patch(file,
                Map.of("testFoo", List.of()),
                Map.of(),
                new PrintWriter(sw));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(written.contains("@Tag(\"security\")"), written);
    }

    // -------------------------------------------------------------------------
    // JUnit 4 / @Category patching
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("applyDesiredStateJunit4 writes @Category for a single mapped tag")
    @Tag("positive")
    void applyDesiredStateJunit4_singleTag_writesCategory() {
        MethodDeclaration method = firstMethod("class C { @org.junit.Test void m() {} }");
        Map<String, String> classMap = Map.of("security", "com.example.SecurityTest");
        StringWriter sw = new StringWriter();
        JavaSourcePatcher.MethodApplyResult result = JavaSourcePatcher.applyDesiredStateJunit4(
                method, List.of("security"), classMap, new PrintWriter(sw));
        assertTrue(result.modified());
        assertTrue(result.needsCategoryImport());
        assertEquals(Set.of("com.example.SecurityTest"), result.addedCategoryFqcns());
        String out = method.toString();
        assertTrue(out.contains("@Category(SecurityTest.class)"), out);
    }

    @Test
    @DisplayName("applyDesiredStateJunit4 writes array-form @Category for multiple mapped tags")
    @Tag("positive")
    void applyDesiredStateJunit4_multipleTags_writesArrayCategory() {
        MethodDeclaration method = firstMethod("class C { @org.junit.Test void m() {} }");
        Map<String, String> classMap = Map.of(
                "security", "com.example.SecurityTest",
                "performance", "com.example.PerformanceTest");
        StringWriter sw = new StringWriter();
        JavaSourcePatcher.MethodApplyResult result = JavaSourcePatcher.applyDesiredStateJunit4(
                method, List.of("security", "performance"), classMap, new PrintWriter(sw));
        assertTrue(result.modified());
        assertEquals(2, result.tagsAdded());
        String out = method.toString();
        assertTrue(out.contains("@Category"), out);
        assertTrue(out.contains("SecurityTest.class"), out);
        assertTrue(out.contains("PerformanceTest.class"), out);
    }

    @Test
    @DisplayName("applyDesiredStateJunit4 skips unmapped tag with warning and leaves no partial @Category")
    @Tag("edge-case")
    void applyDesiredStateJunit4_unmappedTag_skipsWithWarning() {
        MethodDeclaration method = firstMethod("class C { @org.junit.Test void m() {} }");
        StringWriter sw = new StringWriter();
        JavaSourcePatcher.MethodApplyResult result = JavaSourcePatcher.applyDesiredStateJunit4(
                method, List.of("unknown-tag"), Map.of(), new PrintWriter(sw));
        assertFalse(result.modified());
        assertTrue(sw.toString().contains("[WARN]"));
    }

    @Test
    @DisplayName("applyDesiredStateJunit4 removes existing @Category when desired list is empty")
    @Tag("positive")
    void applyDesiredStateJunit4_emptyDesired_removesCategory() {
        MethodDeclaration method = firstMethod(
                "class C { @org.junit.Test @Category(SecurityTest.class) void m() {} }");
        StringWriter sw = new StringWriter();
        JavaSourcePatcher.MethodApplyResult result = JavaSourcePatcher.applyDesiredStateJunit4(
                method, List.of(), Map.of(), new PrintWriter(sw));
        assertTrue(result.modified());
        assertEquals(0, result.tagsAdded());
        assertTrue(result.tagsRemoved() > 0);
        assertFalse(method.toString().contains("@Category"));
    }

    @Test
    @DisplayName("applyDesiredStateJunit4 does not modify when existing categories match desired")
    @Tag("edge-case")
    void applyDesiredStateJunit4_noChange_returnsUnmodified() {
        MethodDeclaration method = firstMethod(
                "class C { @org.junit.Test @Category(SecurityTest.class) void m() {} }");
        Map<String, String> classMap = Map.of("security", "com.example.SecurityTest");
        StringWriter sw = new StringWriter();
        JavaSourcePatcher.MethodApplyResult result = JavaSourcePatcher.applyDesiredStateJunit4(
                method, List.of("security"), classMap, new PrintWriter(sw));
        assertFalse(result.modified());
    }

    @Test
    @DisplayName("patch() uses @Category for file with JUnit 4 imports and writes category class imports")
    @Tag("integration")
    void patch_junit4File_writesCategoryAndImports(@TempDir Path tempDir) throws IOException {
        // Category class is in a different package so the import is required
        String source = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "public class FooTest {\n"
                + "    @Test\n"
                + "    public void shouldDoSomething() {}\n"
                + "}\n";
        Path file = tempDir.resolve("FooTest.java");
        Files.writeString(file, source, StandardCharsets.UTF_8);

        JavaSourcePatcher patcher = new JavaSourcePatcher();
        patcher.configure(new TestDiscoveryConfig(
                List.of("Test.java"),
                Set.of(),
                Map.of("categoryClasses", List.of("security=com.example.categories.SecurityTest"))));

        StringWriter sw = new StringWriter();
        int changes = patcher.patch(file,
                Map.of("shouldDoSomething", List.of("security")),
                Map.of(),
                new PrintWriter(sw));

        assertTrue(changes > 0);
        String patched = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(patched.contains("@Category(SecurityTest.class)"), patched);
        assertTrue(patched.contains("import org.junit.experimental.categories.Category"), patched);
        assertTrue(patched.contains("import com.example.categories.SecurityTest"), patched);
    }

    @Test
    @DisplayName("patch() emits diagnostic and skips @DisplayName for JUnit 4 file")
    @Tag("integration")
    void patch_junit4FileWithDisplayName_skipsDisplayNameWithDiagnostic(@TempDir Path tempDir)
            throws IOException {
        String source = "package com.example;\n"
                + "import org.junit.Test;\n"
                + "public class FooTest {\n"
                + "    @Test\n"
                + "    public void shouldDoSomething() {}\n"
                + "}\n";
        Path file = tempDir.resolve("FooTest.java");
        Files.writeString(file, source, StandardCharsets.UTF_8);

        JavaSourcePatcher patcher = new JavaSourcePatcher();
        patcher.configure(new TestDiscoveryConfig(
                List.of("Test.java"),
                Set.of(),
                Map.of("categoryClasses", List.of("security=com.example.SecurityTest"))));

        StringWriter sw = new StringWriter();
        patcher.patch(file,
                Map.of("shouldDoSomething", List.of("security")),
                Map.of("shouldDoSomething", "My display name"),
                new PrintWriter(sw));

        String diagnostic = sw.toString();
        assertTrue(diagnostic.contains("[WARN]"), diagnostic);
        String patched = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(patched.contains("DisplayName"), patched);
    }

    @Test
    @DisplayName("configure() sets JUNIT4 default when tagFramework=junit4 property is set")
    @Tag("positive")
    void configure_tagFrameworkJunit4_setsDefaultFramework(@TempDir Path tempDir) throws IOException {
        // No framework imports — relies on tagFramework=junit4 default
        String source = "package com.example;\n"
                + "public class FooTest {\n"
                + "    @org.junit.Test\n"
                + "    public void shouldDoSomething() {}\n"
                + "}\n";
        Path file = tempDir.resolve("FooTest.java");
        Files.writeString(file, source, StandardCharsets.UTF_8);

        JavaSourcePatcher patcher = new JavaSourcePatcher();
        patcher.configure(new TestDiscoveryConfig(
                List.of("Test.java"),
                Set.of(),
                Map.of(
                    "tagFramework", List.of("junit4"),
                    "categoryClasses", List.of("security=com.example.SecurityTest"))));

        StringWriter sw = new StringWriter();
        int changes = patcher.patch(file,
                Map.of("shouldDoSomething", List.of("security")),
                Map.of(),
                new PrintWriter(sw));

        assertTrue(changes > 0);
        String patched = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(patched.contains("@Category(SecurityTest.class)"), patched);
    }

    @Test
    @DisplayName("MethodApplyResult.needsCategoryImport returns true only when category FQCNs were added")
    @Tag("positive")
    void methodApplyResult_needsCategoryImport() {
        JavaSourcePatcher.MethodApplyResult withCategory =
                new JavaSourcePatcher.MethodApplyResult(1, 0, false, Set.of("com.example.Foo"));
        JavaSourcePatcher.MethodApplyResult withTag =
                new JavaSourcePatcher.MethodApplyResult(1, 0, false, Set.of());
        assertTrue(withCategory.needsCategoryImport());
        assertFalse(withCategory.needsTagImport());
        assertFalse(withTag.needsCategoryImport());
        assertTrue(withTag.needsTagImport());
    }
}
