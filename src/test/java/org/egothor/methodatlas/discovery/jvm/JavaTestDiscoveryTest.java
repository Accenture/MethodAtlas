package org.egothor.methodatlas.discovery.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;

/**
 * Unit and integration tests for {@link JavaTestDiscovery}.
 *
 * <p>
 * Each test writes real {@code .java} source files to a temporary directory so
 * that JavaParser can parse them exactly as it would during a real scan. No
 * mocks or stubs are used.
 * </p>
 */
@Tag("unit")
@Tag("discovery")
class JavaTestDiscoveryTest {

    private JavaParser parser;
    private JavaTestDiscovery discovery;

    @BeforeEach
    void setUp() {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(LanguageLevel.JAVA_21);
        parser = new JavaParser(cfg);
        discovery = new JavaTestDiscovery(parser, List.of("Test.java"), AnnotationInspector.DEFAULT_TEST_ANNOTATIONS);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static Path write(Path dir, String relativePath, String source) throws IOException {
        Path file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private List<DiscoveredMethod> discover(Path root) throws IOException {
        return discovery.discover(root).collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Empty directory
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("empty directory produces empty stream")
    @Tag("positive")
    void discover_emptyDirectory_returnsEmptyStream(@TempDir Path tmp) throws IOException {
        List<DiscoveredMethod> result = discover(tmp);
        assertTrue(result.isEmpty());
        assertFalse(discovery.hadErrors());
    }

    // -------------------------------------------------------------------------
    // Basic JUnit 5 discovery
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("single JUnit 5 @Test method is discovered")
    @Tag("positive")
    void discover_singleJunit5Test_discovered(@TempDir Path tmp) throws IOException {
        write(tmp, "com/acme/FooTest.java", """
                package com.acme;
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test
                    void happyPath() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        DiscoveredMethod m = result.get(0);
        assertEquals("com.acme.FooTest", m.fqcn());
        assertEquals("happyPath", m.method());
        assertFalse(discovery.hadErrors());
    }

    @Test
    @DisplayName("multiple test methods in one class are all discovered")
    @Tag("positive")
    void discover_multipleTestMethods_allDiscovered(@TempDir Path tmp) throws IOException {
        write(tmp, "com/acme/FooTest.java", """
                package com.acme;
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void alpha() {}
                    @Test void beta() {}
                    @Test void gamma() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(3, result.size());
        List<String> names = result.stream().map(DiscoveredMethod::method).toList();
        assertTrue(names.containsAll(List.of("alpha", "beta", "gamma")));
    }

    @Test
    @DisplayName("@ParameterizedTest method is discovered")
    @Tag("positive")
    void discover_parameterizedTest_discovered(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.ValueSource;
                class FooTest {
                    @ParameterizedTest
                    @ValueSource(strings = {"a", "b"})
                    void withParam(String s) {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertEquals("withParam", result.get(0).method());
    }

    @Test
    @DisplayName("@RepeatedTest method is discovered")
    @Tag("positive")
    void discover_repeatedTest_discovered(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.RepeatedTest;
                class FooTest {
                    @RepeatedTest(3)
                    void repeated() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertEquals("repeated", result.get(0).method());
    }

    // -------------------------------------------------------------------------
    // Tags and DisplayName extraction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("@Tag values on discovered method are extracted")
    @Tag("positive")
    void discover_tagsPresent_extractedCorrectly(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.Tag;
                class FooTest {
                    @Test
                    @Tag("security")
                    @Tag("auth")
                    void login() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        List<String> tags = result.get(0).tags();
        assertTrue(tags.contains("security"), "expected 'security' tag");
        assertTrue(tags.contains("auth"), "expected 'auth' tag");
    }

    @Test
    @DisplayName("@DisplayName value is extracted")
    @Tag("positive")
    void discover_displayNamePresent_extractedCorrectly(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.DisplayName;
                class FooTest {
                    @Test
                    @DisplayName("SECURITY: verifies auth token")
                    void login() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertEquals("SECURITY: verifies auth token", result.get(0).displayName());
    }

    @Test
    @DisplayName("method with no @Tag has empty tags list")
    @Tag("positive")
    void discover_noTags_emptyTagList(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void plain() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertNotNull(result.get(0).tags());
        assertTrue(result.get(0).tags().isEmpty());
    }

    @Test
    @DisplayName("method with no @DisplayName has null displayName")
    @Tag("positive")
    void discover_noDisplayName_nullDisplayName(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void plain() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        // null means annotation absent
        assertTrue(result.get(0).displayName() == null || result.get(0).displayName().isEmpty());
    }

    // -------------------------------------------------------------------------
    // LOC and line numbers
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("LOC is at least 1 for any discovered method")
    @Tag("positive")
    void discover_locAtLeastOne(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void oneliner() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertTrue(result.get(0).loc() >= 1);
    }

    @Test
    @DisplayName("multi-line method has LOC > 1")
    @Tag("positive")
    void discover_multiLineMethod_locGreaterThanOne(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test
                    void multiLine() {
                        int x = 1;
                        int y = 2;
                        int z = x + y;
                    }
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertTrue(result.get(0).loc() > 1, "expected LOC > 1 for multi-line method");
    }

    @Test
    @DisplayName("beginLine and endLine are positive for discovered methods")
    @Tag("positive")
    void discover_lineNumbers_positive(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void foo() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertTrue(result.get(0).beginLine() > 0, "beginLine should be positive");
        assertTrue(result.get(0).endLine() >= result.get(0).beginLine(), "endLine >= beginLine");
    }

    // -------------------------------------------------------------------------
    // filePath and fileStem
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("filePath is set to the absolute path of the source file")
    @Tag("positive")
    void discover_filePath_absoluteAndExists(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void foo() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        Path fp = result.get(0).filePath();
        assertNotNull(fp);
        assertTrue(Files.exists(fp), "filePath should point to an existing file");
    }

    @Test
    @DisplayName("fileStem for a flat file matches class name")
    @Tag("positive")
    void discover_fileStem_flatFile_matchesClassName(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void foo() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertEquals("FooTest", result.get(0).fileStem());
    }

    @Test
    @DisplayName("fileStem for a packaged file reflects the package path")
    @Tag("positive")
    void discover_fileStem_packagedFile_includesPackagePath(@TempDir Path tmp) throws IOException {
        write(tmp, "com/acme/FooTest.java", """
                package com.acme;
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void foo() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertEquals("com.acme.FooTest", result.get(0).fileStem());
    }

    // -------------------------------------------------------------------------
    // sourceContent
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sourceContent returns non-empty class source")
    @Tag("positive")
    void discover_sourceContent_nonEmpty(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void foo() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        Optional<String> src = result.get(0).sourceContent().get();
        assertTrue(src.isPresent(), "sourceContent should return non-empty Optional");
        assertFalse(src.get().isBlank(), "sourceContent should return non-blank class source");
    }

    @Test
    @DisplayName("methods from the same class share the same sourceContent instance")
    @Tag("positive")
    void discover_sameClass_sharedSourceContent(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void alpha() {}
                    @Test void beta() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(2, result.size());
        assertNotNull(result.get(0).sourceContent());
        assertNotNull(result.get(1).sourceContent());
        // Both should return the same class source string
        assertEquals(
                result.get(0).sourceContent().get(),
                result.get(1).sourceContent().get()
        );
    }

    // -------------------------------------------------------------------------
    // Non-test methods are not discovered
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("methods without a test annotation are not discovered")
    @Tag("negative")
    void discover_nonTestMethod_notDiscovered(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                class FooTest {
                    void setUp() {}
                    void helper() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertTrue(result.isEmpty(), "non-annotated methods should not be discovered");
    }

    // -------------------------------------------------------------------------
    // File suffix filtering
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("files not matching the configured suffix are not scanned")
    @Tag("positive")
    void discover_wrongSuffix_fileSkipped(@TempDir Path tmp) throws IOException {
        // discovery is configured for "Test.java" only
        write(tmp, "FooSpec.java", """
                import org.junit.jupiter.api.Test;
                class FooSpec {
                    @Test void foo() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertTrue(result.isEmpty(), "file with non-matching suffix should be skipped");
    }

    @Test
    @DisplayName("multiple configured suffixes are all honoured")
    @Tag("positive")
    void discover_multipleSuffixes_allHonoured(@TempDir Path tmp) throws IOException {
        JavaTestDiscovery multi = new JavaTestDiscovery(parser, List.of("Test.java", "Spec.java"),
                AnnotationInspector.DEFAULT_TEST_ANNOTATIONS);

        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void test1() {}
                }
                """);
        write(tmp, "BarSpec.java", """
                import org.junit.jupiter.api.Test;
                class BarSpec {
                    @Test void spec1() {}
                }
                """);
        write(tmp, "Baz.java", """
                import org.junit.jupiter.api.Test;
                class Baz {
                    @Test void ignored() {}
                }
                """);

        List<DiscoveredMethod> result = multi.discover(tmp).collect(Collectors.toList());

        assertEquals(2, result.size());
        List<String> methods = result.stream().map(DiscoveredMethod::method).toList();
        assertTrue(methods.contains("test1"));
        assertTrue(methods.contains("spec1"));
    }

    // -------------------------------------------------------------------------
    // Multiple files in subtree
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("multiple test classes in a subtree are all discovered")
    @Tag("positive")
    void discover_multipleFiles_allDiscovered(@TempDir Path tmp) throws IOException {
        write(tmp, "com/acme/FooTest.java", """
                package com.acme;
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void a() {}
                }
                """);
        write(tmp, "com/acme/BarTest.java", """
                package com.acme;
                import org.junit.jupiter.api.Test;
                class BarTest {
                    @Test void b() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(2, result.size());
        Set<String> fqcns = result.stream().map(DiscoveredMethod::fqcn).collect(Collectors.toSet());
        assertTrue(fqcns.contains("com.acme.FooTest"));
        assertTrue(fqcns.contains("com.acme.BarTest"));
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("test methods in inner classes are discovered")
    @Tag("positive")
    void discover_innerClass_testMethodsDiscovered(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.Nested;
                class FooTest {
                    @Test void outer() {}

                    @Nested
                    class InnerTest {
                        @Test void inner() {}
                    }
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        List<String> methods = result.stream().map(DiscoveredMethod::method).toList();
        assertTrue(methods.contains("outer"), "outer test method should be discovered");
        assertTrue(methods.contains("inner"), "inner test method should be discovered");
    }

    @Test
    @DisplayName("inner class has its own FQCN (not the outer class FQCN)")
    @Tag("positive")
    void discover_innerClass_ownFqcn(@TempDir Path tmp) throws IOException {
        write(tmp, "com/acme/FooTest.java", """
                package com.acme;
                import org.junit.jupiter.api.Test;
                class FooTest {
                    class InnerTest {
                        @Test void inner() {}
                    }
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        List<String> fqcns = result.stream().map(DiscoveredMethod::fqcn).toList();
        assertTrue(fqcns.stream().anyMatch(f -> f.contains("InnerTest")),
                "inner class should have its own FQCN");
    }

    // -------------------------------------------------------------------------
    // Framework auto-detection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("JUnit 4 @Test is discovered when JUnit 4 import is present")
    @Tag("positive")
    void discover_junit4Test_discovered(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.Test;
                class FooTest {
                    @Test
                    public void junit4Test() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertEquals("junit4Test", result.get(0).method());
    }

    @Test
    @DisplayName("TestNG @Test is discovered when TestNG import is present")
    @Tag("positive")
    void discover_testNgTest_discovered(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.testng.annotations.Test;
                class FooTest {
                    @Test
                    public void ngTest() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertEquals("ngTest", result.get(0).method());
    }

    // -------------------------------------------------------------------------
    // Parse error recovery
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("unparseable file sets hadErrors() without throwing")
    @Tag("negative")
    void discover_unparseableFile_setsHadErrors(@TempDir Path tmp) throws IOException {
        write(tmp, "BrokenTest.java", """
                this is not valid java @@@@
                """);

        List<DiscoveredMethod> result = discover(tmp);

        // The broken file is skipped; no methods discovered
        assertTrue(result.isEmpty());
        assertTrue(discovery.hadErrors(), "hadErrors() should be true after parse failure");
    }

    @Test
    @DisplayName("parse error in one file does not prevent discovery of other files")
    @Tag("negative")
    void discover_brokenAndValidFile_validFileStillDiscovered(@TempDir Path tmp) throws IOException {
        write(tmp, "BrokenTest.java", "not java @@");
        write(tmp, "GoodTest.java", """
                import org.junit.jupiter.api.Test;
                class GoodTest {
                    @Test void good() {}
                }
                """);

        List<DiscoveredMethod> result = discover(tmp);

        assertEquals(1, result.size());
        assertEquals("good", result.get(0).method());
        assertTrue(discovery.hadErrors());
    }

    // -------------------------------------------------------------------------
    // Cumulative hadErrors() across multiple discover() calls
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hadErrors() is false when no errors occurred across two calls")
    @Tag("positive")
    void hadErrors_noErrors_falseAfterTwoCalls(@TempDir Path tmp1, @TempDir Path tmp2) throws IOException {
        write(tmp1, "ATest.java", """
                import org.junit.jupiter.api.Test;
                class ATest { @Test void a() {} }
                """);
        write(tmp2, "BTest.java", """
                import org.junit.jupiter.api.Test;
                class BTest { @Test void b() {} }
                """);

        discovery.discover(tmp1);
        discovery.discover(tmp2);

        assertFalse(discovery.hadErrors());
    }

    @Test
    @DisplayName("hadErrors() stays true after a subsequent clean call")
    @Tag("positive")
    void hadErrors_errorThenClean_remainsTrue(@TempDir Path tmp1, @TempDir Path tmp2) throws IOException {
        write(tmp1, "BrokenTest.java", "not java @@");
        write(tmp2, "CleanTest.java", """
                import org.junit.jupiter.api.Test;
                class CleanTest { @Test void ok() {} }
                """);

        discovery.discover(tmp1);
        boolean afterFirst = discovery.hadErrors();

        discovery.discover(tmp2);
        boolean afterSecond = discovery.hadErrors();

        assertTrue(afterFirst, "should have errors after first (broken) call");
        assertTrue(afterSecond, "errors should be cumulative — remain true after second call");
    }

    // -------------------------------------------------------------------------
    // buildFileStem static method
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("buildFileStem")
    @Tag("unit")
    class BuildFileStemTests {

        @Test
        @DisplayName("flat file: stem equals the class name")
        void buildFileStem_flatFile_stemEqualsClassName(@TempDir Path root) throws IOException {
            Path file = root.resolve("FooTest.java");
            Files.createFile(file);

            String stem = JavaTestDiscovery.buildFileStem(root, file, "FooTest");

            assertEquals("FooTest", stem);
        }

        @Test
        @DisplayName("packaged file: stem equals the package-qualified class path")
        void buildFileStem_packagedFile_packageQualifiedStem(@TempDir Path root) throws IOException {
            Path file = root.resolve("com/acme/FooTest.java");
            Files.createDirectories(file.getParent());
            Files.createFile(file);

            String stem = JavaTestDiscovery.buildFileStem(root, file, "com.acme.FooTest");

            assertEquals("com.acme.FooTest", stem);
        }

        @Test
        @DisplayName("inner class: stem appends inner class simple name to path stem")
        void buildFileStem_innerClass_appendsInnerClassName(@TempDir Path root) throws IOException {
            Path file = root.resolve("com/acme/FooTest.java");
            Files.createDirectories(file.getParent());
            Files.createFile(file);

            // fqcn for inner class differs from path last segment
            String stem = JavaTestDiscovery.buildFileStem(root, file, "com.acme.InnerTest");

            assertEquals("com.acme.FooTest.InnerTest", stem);
        }

        @Test
        @DisplayName("default package: stem equals file name without .java extension")
        void buildFileStem_defaultPackage_noLeadingDot(@TempDir Path root) throws IOException {
            Path file = root.resolve("MyTest.java");
            Files.createFile(file);

            String stem = JavaTestDiscovery.buildFileStem(root, file, "MyTest");

            assertEquals("MyTest", stem);
        }
    }

    // -------------------------------------------------------------------------
    // Custom annotation set
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("custom annotation set discovers only methods with matching annotations")
    @Tag("positive")
    void discover_customAnnotationSet_onlyMatchingAnnotations(@TempDir Path tmp) throws IOException {
        JavaTestDiscovery custom = new JavaTestDiscovery(parser, List.of("Test.java"),
                Set.of("MyCustomTest"));

        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void junit5Test() {}

                    @MyCustomTest
                    void customTest() {}
                }
                """);

        List<DiscoveredMethod> result = custom.discover(tmp).collect(Collectors.toList());

        assertEquals(1, result.size());
        assertEquals("customTest", result.get(0).method());
    }

    // -------------------------------------------------------------------------
    // ServiceLoader lifecycle: no-arg constructor + configure()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("no-arg constructor + configure() discovers test methods correctly")
    @Tag("positive")
    void noArgConstructor_configure_discovers(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void alpha() {}
                    @Test void beta() {}
                }
                """);

        JavaTestDiscovery configured = new JavaTestDiscovery();
        configured.configure(new TestDiscoveryConfig(List.of("Test.java"), Set.of()));

        List<DiscoveredMethod> result = configured.discover(tmp).collect(Collectors.toList());

        assertEquals(2, result.size());
        List<String> methods = result.stream().map(DiscoveredMethod::method).toList();
        assertTrue(methods.contains("alpha"));
        assertTrue(methods.contains("beta"));
    }

    @Test
    @DisplayName("no-arg constructor + configure() with explicit annotations only finds those annotations")
    @Tag("positive")
    void noArgConstructor_configure_customAnnotations(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest {
                    @Test void junit5() {}
                    @MyCustomAnnotation void custom() {}
                }
                """);

        JavaTestDiscovery configured = new JavaTestDiscovery();
        configured.configure(new TestDiscoveryConfig(List.of("Test.java"), Set.of("MyCustomAnnotation")));

        List<DiscoveredMethod> result = configured.discover(tmp).collect(Collectors.toList());

        assertEquals(1, result.size());
        assertEquals("custom", result.get(0).method());
    }

    @Test
    @DisplayName("discover() before configure() throws IllegalStateException")
    @Tag("negative")
    void discover_beforeConfigure_throwsIllegalStateException(@TempDir Path tmp) {
        JavaTestDiscovery unconfigured = new JavaTestDiscovery();

        assertThrows(IllegalStateException.class, () -> unconfigured.discover(tmp),
                "discover() on unconfigured instance should throw IllegalStateException");
    }

    @Test
    @DisplayName("configure() can be re-applied to change suffix filter")
    @Tag("positive")
    void configure_reapply_changesSuffixFilter(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest { @Test void t() {} }
                """);
        write(tmp, "BarSpec.java", """
                import org.junit.jupiter.api.Test;
                class BarSpec { @Test void s() {} }
                """);

        JavaTestDiscovery d = new JavaTestDiscovery();

        // First configure: only Test.java suffix
        d.configure(new TestDiscoveryConfig(List.of("Test.java"), Set.of()));
        List<DiscoveredMethod> first = d.discover(tmp).collect(Collectors.toList());

        // Re-configure: only Spec.java suffix
        d.configure(new TestDiscoveryConfig(List.of("Spec.java"), Set.of()));
        List<DiscoveredMethod> second = d.discover(tmp).collect(Collectors.toList());

        assertEquals(1, first.size());
        assertEquals("t", first.get(0).method());

        assertEquals(1, second.size());
        assertEquals("s", second.get(0).method());
    }

    // -------------------------------------------------------------------------
    // ServiceLoader registration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ServiceLoader finds JavaTestDiscovery via META-INF/services registration")
    @Tag("positive")
    void serviceLoader_findsJavaTestDiscovery() {
        ServiceLoader<TestDiscovery> loader = ServiceLoader.load(TestDiscovery.class);
        List<TestDiscovery> providers = StreamSupport.stream(loader.spliterator(), false)
                .collect(Collectors.toList());

        assertTrue(providers.stream().anyMatch(p -> p instanceof JavaTestDiscovery),
                "ServiceLoader should find JavaTestDiscovery via META-INF/services");
    }

    @Test
    @DisplayName("ServiceLoader-loaded provider works end-to-end after configure()")
    @Tag("positive")
    void serviceLoader_loadedProvider_worksAfterConfigure(@TempDir Path tmp) throws IOException {
        write(tmp, "FooTest.java", """
                import org.junit.jupiter.api.Test;
                class FooTest { @Test void slTest() {} }
                """);

        ServiceLoader<TestDiscovery> loader = ServiceLoader.load(TestDiscovery.class);
        TestDiscovery provider = StreamSupport.stream(loader.spliterator(), false)
                .filter(p -> p instanceof JavaTestDiscovery)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("JavaTestDiscovery not found via ServiceLoader"));

        provider.configure(new TestDiscoveryConfig(List.of("Test.java"), Set.of()));
        List<DiscoveredMethod> result = provider.discover(tmp).collect(Collectors.toList());

        assertEquals(1, result.size());
        assertEquals("slTest", result.get(0).method());
        assertFalse(provider.hadErrors());
    }
}
