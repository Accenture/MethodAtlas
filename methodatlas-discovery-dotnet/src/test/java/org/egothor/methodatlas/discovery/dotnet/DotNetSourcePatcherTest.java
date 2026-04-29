package org.egothor.methodatlas.discovery.dotnet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotNetSourcePatcherTest {

    private DotNetSourcePatcher patcher;
    private StringWriter log;
    private PrintWriter  diagnostics;

    @BeforeEach
    void setUp() {
        patcher = new DotNetSourcePatcher();
        patcher.configure(new TestDiscoveryConfig(List.of(".cs"), Set.of(), Map.of()));
        log = new StringWriter();
        diagnostics = new PrintWriter(log);
    }

    private Path writeTempFile(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // ── supports() ───────────────────────────────────────────────────

    @Test
    void supports_csFile_returnsTrue(@TempDir Path dir) throws Exception {
        Path f = writeTempFile(dir, "MyTest.cs", "");
        assertTrue(patcher.supports(f));
    }

    @Test
    void supports_javaFile_returnsFalse(@TempDir Path dir) throws Exception {
        Path f = writeTempFile(dir, "MyTest.java", "");
        assertFalse(patcher.supports(f));
    }

    // ── NUnit tag patching ────────────────────────────────────────────

    @Test
    void patch_nunit_addCategory(@TempDir Path dir) throws Exception {
        String src = """
                using NUnit.Framework;
                namespace Tests {
                    public class Foo {
                        [Test]
                        public void MyTest() { }
                    }
                }
                """;
        Path file = writeTempFile(dir, "Foo.cs", src);

        int changes = patcher.patch(file,
                Map.of("MyTest", List.of("security")),
                Map.of(), diagnostics);

        assertTrue(changes > 0);
        String result = Files.readString(file);
        assertTrue(result.contains("[Category(\"security\")]"),
                "NUnit category attribute must be added");
        assertTrue(result.contains("[Test]"), "[Test] attribute must be preserved");
    }

    @Test
    void patch_nunit_replaceCategory(@TempDir Path dir) throws Exception {
        String src = """
                using NUnit.Framework;
                namespace Tests {
                    public class Foo {
                        [Test]
                        [Category("old")]
                        public void MyTest() { }
                    }
                }
                """;
        Path file = writeTempFile(dir, "Foo.cs", src);

        int changes = patcher.patch(file,
                Map.of("MyTest", List.of("security")),
                Map.of(), diagnostics);

        assertTrue(changes > 0);
        String result = Files.readString(file);
        assertTrue(result.contains("[Category(\"security\")]"));
        assertFalse(result.contains("[Category(\"old\")]"),
                "Old category must be removed");
    }

    @Test
    void patch_nunit_removeAllTags(@TempDir Path dir) throws Exception {
        String src = """
                using NUnit.Framework;
                namespace Tests {
                    public class Foo {
                        [Test]
                        [Category("security")]
                        public void MyTest() { }
                    }
                }
                """;
        Path file = writeTempFile(dir, "Foo.cs", src);

        int changes = patcher.patch(file,
                Map.of("MyTest", List.of()),  // empty desired list = remove all tags
                Map.of(), diagnostics);

        assertTrue(changes > 0);
        String result = Files.readString(file);
        assertFalse(result.contains("[Category("));
    }

    // ── xUnit tag patching ────────────────────────────────────────────

    @Test
    void patch_xunit_addTrait(@TempDir Path dir) throws Exception {
        String src = """
                using Xunit;
                namespace Tests {
                    public class Foo {
                        [Fact]
                        public void MyTest() { }
                    }
                }
                """;
        Path file = writeTempFile(dir, "Foo.cs", src);

        int changes = patcher.patch(file,
                Map.of("MyTest", List.of("security")),
                Map.of(), diagnostics);

        assertTrue(changes > 0);
        String result = Files.readString(file);
        assertTrue(result.contains("[Trait(\"Tag\", \"security\")]"),
                "xUnit Trait attribute must be added");
    }

    // ── MSTest tag patching ───────────────────────────────────────────

    @Test
    void patch_mstest_addTestCategory(@TempDir Path dir) throws Exception {
        String src = """
                using Microsoft.VisualStudio.TestTools.UnitTesting;
                namespace Tests {
                    [TestClass]
                    public class Foo {
                        [TestMethod]
                        public void MyTest() { }
                    }
                }
                """;
        Path file = writeTempFile(dir, "Foo.cs", src);

        int changes = patcher.patch(file,
                Map.of("MyTest", List.of("security")),
                Map.of(), diagnostics);

        assertTrue(changes > 0);
        String result = Files.readString(file);
        assertTrue(result.contains("[TestCategory(\"security\")]"),
                "MSTest TestCategory attribute must be added");
    }

    // ── xUnit display name patching ───────────────────────────────────

    @Test
    void patch_xunit_addDisplayName(@TempDir Path dir) throws Exception {
        String src = """
                using Xunit;
                namespace Tests {
                    public class Foo {
                        [Fact]
                        public void MyTest() { }
                    }
                }
                """;
        Path file = writeTempFile(dir, "Foo.cs", src);

        int changes = patcher.patch(file,
                Map.of(),
                Map.of("MyTest", "My display name"), diagnostics);

        assertTrue(changes > 0);
        String result = Files.readString(file);
        assertTrue(result.contains("DisplayName = \"My display name\""),
                "xUnit DisplayName must be added to [Fact]");
    }

    @Test
    void patch_xunit_replaceDisplayName(@TempDir Path dir) throws Exception {
        String src = """
                using Xunit;
                namespace Tests {
                    public class Foo {
                        [Fact(DisplayName = "old name")]
                        public void MyTest() { }
                    }
                }
                """;
        Path file = writeTempFile(dir, "Foo.cs", src);

        int changes = patcher.patch(file,
                Map.of(),
                Map.of("MyTest", "new name"), diagnostics);

        assertTrue(changes > 0);
        String result = Files.readString(file);
        assertTrue(result.contains("\"new name\""));
        assertFalse(result.contains("\"old name\""));
    }

    @Test
    void patch_xunit_removeDisplayName(@TempDir Path dir) throws Exception {
        String src = """
                using Xunit;
                namespace Tests {
                    public class Foo {
                        [Fact(DisplayName = "old name")]
                        public void MyTest() { }
                    }
                }
                """;
        Path file = writeTempFile(dir, "Foo.cs", src);

        int changes = patcher.patch(file,
                Map.of(),
                Map.of("MyTest", ""), diagnostics);

        assertTrue(changes > 0);
        String result = Files.readString(file);
        assertFalse(result.contains("DisplayName"));
        assertTrue(result.contains("[Fact]"));
    }

    // ── Method not in map is unchanged ────────────────────────────────

    @Test
    void patch_methodNotInMaps_unchanged(@TempDir Path dir) throws Exception {
        String src = """
                using NUnit.Framework;
                namespace Tests {
                    public class Foo {
                        [Test]
                        [Category("security")]
                        public void MyTest() { }
                    }
                }
                """;
        Path file = writeTempFile(dir, "Foo.cs", src);
        String original = Files.readString(file);

        int changes = patcher.patch(file,
                Map.of("OtherMethod", List.of("tag")),
                Map.of(), diagnostics);

        assertEquals(0, changes);
        assertEquals(original, Files.readString(file), "File must be unchanged");
    }

    // ── No-op when maps are empty ─────────────────────────────────────

    @Test
    void patch_emptyMaps_returnsZero(@TempDir Path dir) throws Exception {
        Path file = writeTempFile(dir, "Foo.cs", "using NUnit.Framework;\n");
        int changes = patcher.patch(file, Map.of(), Map.of(), diagnostics);
        assertEquals(0, changes);
    }
}
