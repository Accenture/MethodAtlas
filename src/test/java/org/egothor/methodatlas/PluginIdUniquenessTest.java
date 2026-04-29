package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscovery;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the plugin-ID uniqueness checks in {@link MethodAtlasApp}
 * throw {@link IllegalStateException} when two providers share the same ID
 * and pass silently when all IDs are distinct.
 *
 * <p>
 * Both {@link MethodAtlasApp#requireUniqueDiscoveryIds} and
 * {@link MethodAtlasApp#requireUniquePatcherIds} are tested with:
 * <ul>
 *   <li>an empty list — should never throw</li>
 *   <li>a list of providers with distinct IDs — should not throw</li>
 *   <li>a list containing a duplicate ID — must throw {@link IllegalStateException}
 *       whose message names the conflicting ID</li>
 * </ul>
 * </p>
 */
class PluginIdUniquenessTest {

    // ── Minimal stub implementations ─────────────────────────────────────────

    private static TestDiscovery discoveryWithId(String id) {
        return new TestDiscovery() {
            @Override public String pluginId() { return id; }
            @Override public Stream<DiscoveredMethod> discover(Path root) { return Stream.empty(); }
            @Override public boolean hadErrors() { return false; }
        };
    }

    private static SourcePatcher patcherWithId(String id) {
        return new SourcePatcher() {
            @Override public String pluginId() { return id; }
            @Override public boolean supports(Path f) { return false; }
            @Override public int patch(Path f, Map<String, List<String>> t,
                                       Map<String, String> d, PrintWriter w) { return 0; }
        };
    }

    // ── TestDiscovery uniqueness ──────────────────────────────────────────────

    @Test
    void requireUniqueDiscoveryIds_emptyList_doesNotThrow() {
        assertDoesNotThrow(() -> MethodAtlasApp.requireUniqueDiscoveryIds(List.of()));
    }

    @Test
    void requireUniqueDiscoveryIds_distinctIds_doesNotThrow() {
        assertDoesNotThrow(() -> MethodAtlasApp.requireUniqueDiscoveryIds(
                List.of(discoveryWithId("java"), discoveryWithId("dotnet"))));
    }

    @Test
    void requireUniqueDiscoveryIds_duplicateId_throwsWithIdInMessage() {
        var ex = assertThrows(IllegalStateException.class,
                () -> MethodAtlasApp.requireUniqueDiscoveryIds(
                        List.of(discoveryWithId("java"),
                                discoveryWithId("dotnet"),
                                discoveryWithId("java"))));
        assertEquals(true, ex.getMessage().contains("java"),
                "Exception message should name the duplicate ID");
    }

    // ── SourcePatcher uniqueness ──────────────────────────────────────────────

    @Test
    void requireUniquePatcherIds_emptyList_doesNotThrow() {
        assertDoesNotThrow(() -> MethodAtlasApp.requireUniquePatcherIds(List.of()));
    }

    @Test
    void requireUniquePatcherIds_distinctIds_doesNotThrow() {
        assertDoesNotThrow(() -> MethodAtlasApp.requireUniquePatcherIds(
                List.of(patcherWithId("java"), patcherWithId("dotnet"))));
    }

    @Test
    void requireUniquePatcherIds_duplicateId_throwsWithIdInMessage() {
        var ex = assertThrows(IllegalStateException.class,
                () -> MethodAtlasApp.requireUniquePatcherIds(
                        List.of(patcherWithId("dotnet"),
                                patcherWithId("dotnet"))));
        assertEquals(true, ex.getMessage().contains("dotnet"),
                "Exception message should name the duplicate ID");
    }
}
