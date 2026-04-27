package org.egothor.methodatlas.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link DiscoveredMethod} record.
 *
 * <p>
 * Verifies that field accessors return exactly the values passed to the
 * constructor, covers the nullable {@code displayName} contract (null = absent,
 * empty = present but empty), validates that line sentinel 0 is supported, and
 * checks the {@link SourceContent} lazy-evaluation contract.
 * </p>
 */
@Tag("unit")
@Tag("api")
class DiscoveredMethodTest {

    private static final Path DUMMY_PATH = Path.of("com/acme/FooTest.java");
    private static final SourceContent EMPTY_CONTENT = () -> Optional.empty();
    private static final SourceContent SOME_CONTENT = () -> Optional.of("class FooTest {}");

    // -------------------------------------------------------------------------
    // Field accessors
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("constructor stores all fields and accessors return them verbatim")
    @Tag("positive")
    void allFields_returnedVerbatim() {
        List<String> tags = List.of("security", "auth");
        DiscoveredMethod m = new DiscoveredMethod(
                "com.acme.FooTest",
                "testLogin",
                10, 20, 11,
                tags,
                "SECURITY: verifies login",
                DUMMY_PATH,
                "com.acme.FooTest",
                SOME_CONTENT);

        assertEquals("com.acme.FooTest", m.fqcn());
        assertEquals("testLogin", m.method());
        assertEquals(10, m.beginLine());
        assertEquals(20, m.endLine());
        assertEquals(11, m.loc());
        assertEquals(tags, m.tags());
        assertEquals("SECURITY: verifies login", m.displayName());
        assertSame(DUMMY_PATH, m.filePath());
        assertEquals("com.acme.FooTest", m.fileStem());
        assertSame(SOME_CONTENT, m.sourceContent());
    }

    // -------------------------------------------------------------------------
    // displayName nullability contract
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("displayName null means annotation absent")
    @Tag("positive")
    void displayName_null_annotationAbsent() {
        DiscoveredMethod m = new DiscoveredMethod(
                "FooTest", "t", 1, 1, 1, List.of(), null, DUMMY_PATH, "FooTest", EMPTY_CONTENT);

        assertNull(m.displayName(), "null displayName means @DisplayName annotation is absent");
    }

    @Test
    @DisplayName("displayName empty string means annotation present with empty value")
    @Tag("positive")
    void displayName_empty_annotationPresentButEmpty() {
        DiscoveredMethod m = new DiscoveredMethod(
                "FooTest", "t", 1, 1, 1, List.of(), "", DUMMY_PATH, "FooTest", EMPTY_CONTENT);

        assertNotNull(m.displayName());
        assertTrue(m.displayName().isEmpty(),
                "empty displayName means @DisplayName annotation is present but has no value");
    }

    @Test
    @DisplayName("displayName non-blank is returned as-is")
    @Tag("positive")
    void displayName_nonBlank_returnedAsIs() {
        DiscoveredMethod m = new DiscoveredMethod(
                "FooTest", "t", 1, 1, 1, List.of(), "Auth Test", DUMMY_PATH, "FooTest", EMPTY_CONTENT);

        assertEquals("Auth Test", m.displayName());
    }

    // -------------------------------------------------------------------------
    // Line number sentinels
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("beginLine and endLine may be zero (position unavailable)")
    @Tag("positive")
    void lineNumbers_zeroSentinel_supported() {
        DiscoveredMethod m = new DiscoveredMethod(
                "FooTest", "t", 0, 0, 1, List.of(), null, DUMMY_PATH, "FooTest", EMPTY_CONTENT);

        assertEquals(0, m.beginLine());
        assertEquals(0, m.endLine());
    }

    @Test
    @DisplayName("beginLine equal to endLine is valid for a one-liner method")
    @Tag("positive")
    void lineNumbers_beginEqualsEnd_validForOneLiner() {
        DiscoveredMethod m = new DiscoveredMethod(
                "FooTest", "t", 5, 5, 1, List.of(), null, DUMMY_PATH, "FooTest", EMPTY_CONTENT);

        assertEquals(5, m.beginLine());
        assertEquals(5, m.endLine());
    }

    // -------------------------------------------------------------------------
    // tags
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("empty tags list is allowed and returned unchanged")
    @Tag("positive")
    void tags_emptyList_returned() {
        DiscoveredMethod m = new DiscoveredMethod(
                "FooTest", "t", 1, 1, 1, List.of(), null, DUMMY_PATH, "FooTest", EMPTY_CONTENT);

        assertNotNull(m.tags());
        assertTrue(m.tags().isEmpty());
    }

    @Test
    @DisplayName("tags list with multiple values is returned verbatim")
    @Tag("positive")
    void tags_multipleValues_returned() {
        List<String> tags = List.of("a", "b", "c");
        DiscoveredMethod m = new DiscoveredMethod(
                "FooTest", "t", 1, 1, 1, tags, null, DUMMY_PATH, "FooTest", EMPTY_CONTENT);

        assertEquals(tags, m.tags());
    }

    // -------------------------------------------------------------------------
    // sourceContent lazy evaluation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sourceContent returning Optional.empty() is supported")
    @Tag("positive")
    void sourceContent_empty_optional_supported() {
        DiscoveredMethod m = new DiscoveredMethod(
                "FooTest", "t", 1, 1, 1, List.of(), null, DUMMY_PATH, "FooTest", EMPTY_CONTENT);

        Optional<String> src = m.sourceContent().get();
        assertFalse(src.isPresent(), "sourceContent may return empty Optional");
    }

    @Test
    @DisplayName("sourceContent returning non-empty Optional delivers the class source")
    @Tag("positive")
    void sourceContent_nonEmpty_deliversSource() {
        SourceContent sc = () -> Optional.of("class FooTest { @Test void t() {} }");
        DiscoveredMethod m = new DiscoveredMethod(
                "FooTest", "t", 1, 1, 1, List.of(), null, DUMMY_PATH, "FooTest", sc);

        Optional<String> src = m.sourceContent().get();
        assertTrue(src.isPresent());
        assertEquals("class FooTest { @Test void t() {} }", src.get());
    }

    @Test
    @DisplayName("sourceContent is called lazily — only when .get() is invoked")
    @Tag("positive")
    void sourceContent_lazilyEvaluated() {
        int[] callCount = {0};
        SourceContent lazy = () -> {
            callCount[0]++;
            return Optional.of("source");
        };

        DiscoveredMethod m = new DiscoveredMethod(
                "FooTest", "t", 1, 1, 1, List.of(), null, DUMMY_PATH, "FooTest", lazy);

        // Just holding the record should not trigger evaluation
        assertEquals(0, callCount[0], "sourceContent should not be evaluated during record construction");

        m.sourceContent().get();

        assertEquals(1, callCount[0], "sourceContent should be evaluated once when .get() is called");
    }

    // -------------------------------------------------------------------------
    // Record equality
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("two records with identical field values are equal")
    @Tag("positive")
    void equality_identicalFields_equal() {
        SourceContent sc = () -> Optional.of("x");
        DiscoveredMethod a = new DiscoveredMethod(
                "FooTest", "t", 1, 2, 2, List.of("x"), "dn", DUMMY_PATH, "FooTest", sc);
        DiscoveredMethod b = new DiscoveredMethod(
                "FooTest", "t", 1, 2, 2, List.of("x"), "dn", DUMMY_PATH, "FooTest", sc);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("two records differing in method name are not equal")
    @Tag("positive")
    void equality_differentMethod_notEqual() {
        DiscoveredMethod a = new DiscoveredMethod(
                "FooTest", "alpha", 1, 1, 1, List.of(), null, DUMMY_PATH, "FooTest", EMPTY_CONTENT);
        DiscoveredMethod b = new DiscoveredMethod(
                "FooTest", "beta", 1, 1, 1, List.of(), null, DUMMY_PATH, "FooTest", EMPTY_CONTENT);

        assertFalse(a.equals(b));
    }
}
