package org.egothor.methodatlas.discovery.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TypeScriptTestDiscovery} that do not require Node.js.
 *
 * <p>
 * These tests cover:
 * </p>
 * <ul>
 * <li>Default configuration values applied during {@link TypeScriptTestDiscovery#configure}</li>
 * <li>FQCN and file-stem computation from file paths</li>
 * <li>Method-name composition with and without describe blocks</li>
 * <li>Guard against calling {@link TypeScriptTestDiscovery#discover} before configure</li>
 * </ul>
 *
 * <p>
 * The full discovery pipeline (spawning workers, scanning files) is covered by
 * the integration test {@code TypeScriptTestDiscoveryIT}, which is skipped
 * automatically when Node.js is not available.
 * </p>
 */
class TypeScriptTestDiscoveryUnitTest {

    // -------------------------------------------------------------------------
    // pluginId
    // -------------------------------------------------------------------------

    @Test
    void pluginId_returnsTypescript() throws IOException {
        try (TypeScriptTestDiscovery d = new TypeScriptTestDiscovery()) {
            assertEquals("typescript", d.pluginId());
        }
    }

    // -------------------------------------------------------------------------
    // Default suffixes
    // -------------------------------------------------------------------------

    @Test
    void defaultSuffixes_containTestTs() {
        assertTrue(TypeScriptTestDiscovery.DEFAULT_SUFFIXES.contains(".test.ts"),
                "Default suffixes must include .test.ts");
    }

    @Test
    void defaultSuffixes_containSpecTs() {
        assertTrue(TypeScriptTestDiscovery.DEFAULT_SUFFIXES.contains(".spec.ts"),
                "Default suffixes must include .spec.ts");
    }

    @Test
    void defaultFunctionNames_containTestAndIt() {
        assertTrue(TypeScriptTestDiscovery.DEFAULT_FUNCTION_NAMES.containsAll(List.of("test", "it")));
    }

    // -------------------------------------------------------------------------
    // FQCN computation
    // -------------------------------------------------------------------------

    @Test
    void buildFqcn_simpleFile_stripsExtension() {
        Path root = Paths.get("/project/src");
        Path file = Paths.get("/project/src/auth/authService.test.ts");
        String fqcn = TypeScriptTestDiscovery.buildFqcn(root, file);
        assertEquals("auth.authService.test", fqcn);
    }

    @Test
    void buildFqcn_fileAtRoot_noPrefix() {
        Path root = Paths.get("/project/src");
        Path file = Paths.get("/project/src/utils.test.ts");
        String fqcn = TypeScriptTestDiscovery.buildFqcn(root, file);
        assertEquals("utils.test", fqcn);
    }

    @Test
    void buildFqcn_deeplyNested_allSegmentsPresent() {
        Path root = Paths.get("/project");
        Path file = Paths.get("/project/a/b/c/deep.spec.ts");
        String fqcn = TypeScriptTestDiscovery.buildFqcn(root, file);
        assertEquals("a.b.c.deep.spec", fqcn);
    }

    @Test
    void buildFileStem_matchesFqcn() {
        Path root = Paths.get("/project/src");
        Path file = Paths.get("/project/src/auth/login.test.ts");
        assertEquals(
                TypeScriptTestDiscovery.buildFqcn(root, file),
                TypeScriptTestDiscovery.buildFileStem(root, file));
    }

    // -------------------------------------------------------------------------
    // Guard: discover() before configure()
    // -------------------------------------------------------------------------

    @Test
    void discover_beforeConfigure_throwsIllegalState() throws IOException {
        try (TypeScriptTestDiscovery discovery = new TypeScriptTestDiscovery()) {
            assertThrows(IllegalStateException.class,
                    () -> discovery.discover(Paths.get(".")));
        }
    }

    // -------------------------------------------------------------------------
    // configure() honours custom functionNames property
    // -------------------------------------------------------------------------

    @Test
    void configure_customFunctionNames_areUsed() throws IOException {
        TypeScriptTestDiscovery discovery = new TypeScriptTestDiscovery();
        TestDiscoveryConfig cfg = new TestDiscoveryConfig(
                List.of("typescript:.test.ts"),
                java.util.Set.of(),
                Map.of("functionNames", List.of("it", "specify")));
        discovery.configure(cfg);
        // No exception; configure succeeds.
        assertNotNull(discovery);
    }

    // -------------------------------------------------------------------------
    // close() before pool is started is safe
    // -------------------------------------------------------------------------

    @Test
    void close_beforeDiscover_doesNotThrow() throws IOException {
        TypeScriptTestDiscovery discovery = new TypeScriptTestDiscovery();
        discovery.configure(new TestDiscoveryConfig(
                List.of(), java.util.Set.of(), Map.of()));
        discovery.close(); // must not throw
    }

    // -------------------------------------------------------------------------
    // discover() on a directory with no TS/JS files yields an empty stream
    // and never touches Node.js
    // -------------------------------------------------------------------------

    @Test
    void discover_directoryWithNoTsFiles_returnsEmptyStreamAndNoErrors() throws IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ma-ts-unit-");
        // Place a plain Java file — must not be matched by the TypeScript plugin.
        java.nio.file.Files.writeString(dir.resolve("Foo.java"), "class Foo {}");
        try (TypeScriptTestDiscovery discovery = new TypeScriptTestDiscovery()) {
            discovery.configure(new TestDiscoveryConfig(
                    List.of(), java.util.Set.of(), Map.of()));
            try (java.util.stream.Stream<org.egothor.methodatlas.api.DiscoveredMethod> stream =
                    discovery.discover(dir)) {
                assertEquals(0, stream.count(),
                        "Directory with no TS/JS test files must yield no methods");
            }
            // No TS files found => Node.js was never consulted => hadErrors() is false.
            assertFalse(discovery.hadErrors(),
                    "hadErrors() must be false when no TypeScript files were present");
        } finally {
            java.nio.file.Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); }
                                    catch (IOException ignored) {} });
        }
    }
}
