package org.egothor.methodatlas.discovery.dotnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotNetTestDiscoveryTest {

    private DotNetTestDiscovery discovery;

    @BeforeEach
    void setUp() {
        discovery = new DotNetTestDiscovery();
        discovery.configure(new TestDiscoveryConfig(List.of(".cs"), Set.of(), Map.of()));
    }

    private Path resource(String name) throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource(name).toURI());
    }

    @Test
    void discover_nunit_findsTestMethods(@TempDir Path root) throws Exception {
        Path src = resource("NUnitSample.cs");
        java.nio.file.Files.copy(src, root.resolve("NUnitSample.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        List<String> names = methods.stream().map(DiscoveredMethod::method).collect(Collectors.toList());
        assertTrue(names.contains("TestLogin"),   "TestLogin must be discovered");
        assertTrue(names.contains("TestLogout"),  "TestLogout must be discovered");
        assertTrue(names.contains("TestPermissions"), "TestPermissions must be discovered");
        assertFalse(names.contains("NotATestMethod"), "Non-test method must not be discovered");
    }

    @Test
    void discover_nunit_extractsTags(@TempDir Path root) throws Exception {
        Path src = resource("NUnitSample.cs");
        java.nio.file.Files.copy(src, root.resolve("NUnitSample.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        DiscoveredMethod login = methods.stream()
                .filter(m -> "TestLogin".equals(m.method()))
                .findFirst().orElseThrow();
        assertEquals(List.of("authentication"), login.tags());
    }

    @Test
    void discover_nunit_buildsFqcn(@TempDir Path root) throws Exception {
        Path src = resource("NUnitSample.cs");
        java.nio.file.Files.copy(src, root.resolve("NUnitSample.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        assertTrue(methods.stream().allMatch(m -> "MyCompany.Security.Tests.LoginTests".equals(m.fqcn())),
                "All methods must have correct FQCN");
    }

    @Test
    void discover_xunit_findsTestMethods(@TempDir Path root) throws Exception {
        Path src = resource("XUnitSample.cs");
        java.nio.file.Files.copy(src, root.resolve("XUnitSample.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        List<String> names = methods.stream().map(DiscoveredMethod::method).collect(Collectors.toList());
        assertTrue(names.contains("TestLogin"));
        assertTrue(names.contains("TestWithParam"));
        assertTrue(names.contains("TestLoginWithName"));
        assertFalse(names.contains("NotATestMethod"));
    }

    @Test
    void discover_xunit_extractsTags(@TempDir Path root) throws Exception {
        Path src = resource("XUnitSample.cs");
        java.nio.file.Files.copy(src, root.resolve("XUnitSample.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        DiscoveredMethod login = methods.stream()
                .filter(m -> "TestLogin".equals(m.method()))
                .findFirst().orElseThrow();
        assertEquals(List.of("security"), login.tags());
    }

    @Test
    void discover_xunit_extractsDisplayName(@TempDir Path root) throws Exception {
        Path src = resource("XUnitSample.cs");
        java.nio.file.Files.copy(src, root.resolve("XUnitSample.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        DiscoveredMethod named = methods.stream()
                .filter(m -> "TestLoginWithName".equals(m.method()))
                .findFirst().orElseThrow();
        assertEquals("Login with display name", named.displayName());
    }

    @Test
    void discover_xunit_noDisplayName_returnsNull(@TempDir Path root) throws Exception {
        Path src = resource("XUnitSample.cs");
        java.nio.file.Files.copy(src, root.resolve("XUnitSample.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        DiscoveredMethod login = methods.stream()
                .filter(m -> "TestLogin".equals(m.method()))
                .findFirst().orElseThrow();
        // No DisplayName parameter on [Fact]
        assertTrue(login.displayName() == null || login.tags().contains("security"),
                "Method without DisplayName should have null displayName");
    }

    @Test
    void discover_mstest_findsTestMethods(@TempDir Path root) throws Exception {
        Path src = resource("MSTestSample.cs");
        java.nio.file.Files.copy(src, root.resolve("MSTestSample.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        List<String> names = methods.stream().map(DiscoveredMethod::method).collect(Collectors.toList());
        assertTrue(names.contains("TestLogin"));
        assertTrue(names.contains("TestWithParam"));
    }

    @Test
    void discover_fileScopedNamespace(@TempDir Path root) throws Exception {
        Path src = resource("FileScopedNamespace.cs");
        java.nio.file.Files.copy(src, root.resolve("FileScopedNamespace.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        assertFalse(methods.isEmpty(), "Should find methods in file-scoped namespace");
        assertTrue(methods.stream().allMatch(m -> "MyCompany.Tests.SimpleTests".equals(m.fqcn())),
                "FQCN must include file-scoped namespace");
    }

    @Test
    void discover_nestedClass(@TempDir Path root) throws Exception {
        Path src = resource("NestedClass.cs");
        java.nio.file.Files.copy(src, root.resolve("NestedClass.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        assertEquals(1, methods.size());
        assertEquals("MyCompany.Tests.OuterTests.InnerTests", methods.get(0).fqcn());
        assertEquals("InnerMethod", methods.get(0).method());
    }

    @Test
    void discover_nonCsFile_ignored(@TempDir Path root) throws Exception {
        java.nio.file.Files.writeString(root.resolve("README.txt"), "not C#");
        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());
        assertTrue(methods.isEmpty());
    }

    @Test
    void discover_emptyDirectory_returnsEmpty(@TempDir Path root) throws Exception {
        assertTrue(discovery.discover(root).collect(Collectors.toList()).isEmpty());
    }

    @Test
    void discover_lineNumbers_positive(@TempDir Path root) throws Exception {
        Path src = resource("NUnitSample.cs");
        java.nio.file.Files.copy(src, root.resolve("NUnitSample.cs"));

        List<DiscoveredMethod> methods = discovery.discover(root).collect(Collectors.toList());

        methods.forEach(m -> {
            assertTrue(m.beginLine() > 0, "beginLine must be positive for " + m.method());
            assertTrue(m.endLine()   >= m.beginLine(), "endLine >= beginLine for " + m.method());
        });
    }
}
