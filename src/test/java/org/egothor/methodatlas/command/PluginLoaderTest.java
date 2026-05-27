// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PluginLoader}.
 *
 * <p>
 * Two responsibilities are exercised:
 * </p>
 * <ul>
 *   <li>The pure validation methods {@link PluginLoader#requireUniqueDiscoveryIds}
 *       and {@link PluginLoader#requireUniquePatcherIds} — called directly with
 *       hand-built provider lists to cover the empty, distinct, and duplicate
 *       cases.</li>
 *   <li>The instance-level lifecycle method {@link PluginLoader#closeAll} —
 *       verified to invoke {@code close} on every provider in order even when
 *       one of them throws, mirroring the production contract that a single
 *       failing close must not strand later providers.</li>
 * </ul>
 *
 * <p>
 * The {@link PluginLoader#loadProviders} and {@link PluginLoader#loadPatchers}
 * methods are exercised end-to-end by the existing scan-mode integration
 * tests, which run against the real {@code ServiceLoader} classpath. They are
 * not duplicated here because the {@code ServiceLoader} contract is the unit
 * under test, not anything {@code PluginLoader} adds.
 * </p>
 *
 * @since 1.0.0
 */
class PluginLoaderTest {

    // ── Stub plugin implementations (test fixtures) ──────────────────────────

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
            @Override public boolean supports(Path file) { return false; }
            @Override public int patch(Path file, Map<String, List<String>> tags,
                                       Map<String, String> displayNames, PrintWriter out) { return 0; }
        };
    }

    /**
     * Stub provider that records every {@code close} call into a shared list,
     * optionally throwing on the first close to exercise the
     * {@link PluginLoader#closeAll} resilience contract.
     */
    private static TestDiscovery recordingDiscovery(String id, List<String> closed, boolean throwOnClose) {
        return new TestDiscovery() {
            @Override public String pluginId() { return id; }
            @Override public Stream<DiscoveredMethod> discover(Path root) { return Stream.empty(); }
            @Override public boolean hadErrors() { return false; }
            @Override public void configure(TestDiscoveryConfig config) {
                // intentionally empty: configuration is not exercised in this test
            }
            @Override public void close() throws IOException {
                closed.add(id);
                if (throwOnClose) {
                    throw new IOException("synthetic close failure for " + id);
                }
            }
        };
    }

    // ── TestDiscovery uniqueness ─────────────────────────────────────────────

    @Test
    void requireUniqueDiscoveryIds_emptyList_doesNotThrow() {
        assertDoesNotThrow(() -> PluginLoader.requireUniqueDiscoveryIds(List.of()));
    }

    @Test
    void requireUniqueDiscoveryIds_distinctIds_doesNotThrow() {
        assertDoesNotThrow(() -> PluginLoader.requireUniqueDiscoveryIds(
                List.of(discoveryWithId("java"), discoveryWithId("dotnet"))));
    }

    @Test
    void requireUniqueDiscoveryIds_duplicateId_throwsWithIdInMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PluginLoader.requireUniqueDiscoveryIds(
                        List.of(discoveryWithId("java"),
                                discoveryWithId("dotnet"),
                                discoveryWithId("java"))));
        assertTrue(ex.getMessage().contains("java"),
                "Exception message should name the duplicate ID");
    }

    // ── SourcePatcher uniqueness ─────────────────────────────────────────────

    @Test
    void requireUniquePatcherIds_emptyList_doesNotThrow() {
        assertDoesNotThrow(() -> PluginLoader.requireUniquePatcherIds(List.of()));
    }

    @Test
    void requireUniquePatcherIds_distinctIds_doesNotThrow() {
        assertDoesNotThrow(() -> PluginLoader.requireUniquePatcherIds(
                List.of(patcherWithId("java"), patcherWithId("dotnet"))));
    }

    @Test
    void requireUniquePatcherIds_duplicateId_throwsWithIdInMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PluginLoader.requireUniquePatcherIds(
                        List.of(patcherWithId("dotnet"),
                                patcherWithId("dotnet"))));
        assertTrue(ex.getMessage().contains("dotnet"),
                "Exception message should name the duplicate ID");
    }

    // ── closeAll lifecycle ───────────────────────────────────────────────────

    @Test
    void closeAll_closesEveryProviderInOrder() {
        List<String> closed = new ArrayList<>();
        PluginLoader loader = new PluginLoader();

        loader.closeAll(List.of(
                recordingDiscovery("java", closed, false),
                recordingDiscovery("dotnet", closed, false),
                recordingDiscovery("python", closed, false)));

        assertEquals(List.of("java", "dotnet", "python"), closed,
                "All providers should be closed in registration order");
    }

    @Test
    void closeAll_failureInOneProvider_doesNotPreventOthers() {
        List<String> closed = new ArrayList<>();
        PluginLoader loader = new PluginLoader();

        // The first provider throws IOException during close; the remaining
        // providers must still receive a close() call.
        assertDoesNotThrow(() -> loader.closeAll(List.of(
                recordingDiscovery("java", closed, true),
                recordingDiscovery("dotnet", closed, false),
                recordingDiscovery("python", closed, false))));

        assertEquals(List.of("java", "dotnet", "python"), closed,
                "A throwing close() on one provider must not strand later providers");
    }

    @Test
    void closeAll_emptyList_doesNothing() {
        PluginLoader loader = new PluginLoader();
        assertDoesNotThrow(() -> loader.closeAll(List.of()));
    }

    // ── loadProviders against the live classpath ─────────────────────────────

    @Test
    void loadProviders_realClasspath_returnsAtLeastOneProvider() {
        PluginLoader loader = new PluginLoader();
        TestDiscoveryConfig config = new TestDiscoveryConfig(
                List.of("Test.java"), Set.of("Test"), Map.of());

        List<TestDiscovery> providers = loader.loadProviders(config);
        try {
            assertNotNull(providers);
            assertFalse(providers.isEmpty(),
                    "ServiceLoader should resolve at least one TestDiscovery provider on the test classpath");
        } finally {
            loader.closeAll(providers);
        }
    }
}
