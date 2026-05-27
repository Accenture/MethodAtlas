// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * Resolves and configures discovery plugins via {@link ServiceLoader}.
 *
 * <p>
 * Each plugin JAR ships a service registration file under
 * {@code META-INF/services/} listing its implementation of
 * {@link TestDiscovery} (and, optionally, {@link SourcePatcher}). This loader
 * walks the classpath, instantiates every registered provider, applies the
 * run-time {@link TestDiscoveryConfig} via {@code configure}, and verifies that
 * every provider declares a unique {@code pluginId()}.
 * </p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * Instances are intended to be created once per CLI run and injected into the
 * {@link Command} implementations that need them. The loader itself is
 * stateless — no instance fields — so a single loader can be shared between
 * commands that participate in the same orchestration. The lifecycle of the
 * loaded providers is owned by the caller: a typical usage closes them in a
 * {@code finally} block via {@link #closeAll(List)}.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * This class is thread-safe. {@link ServiceLoader#load(Class)} resolution is
 * idempotent per classloader, and no shared mutable state is maintained.
 * </p>
 *
 * @see TestDiscovery
 * @see SourcePatcher
 * @see Command
 * @since 1.0.0
 */
public final class PluginLoader {

    private static final Logger LOG = Logger.getLogger(PluginLoader.class.getName());

    /**
     * Creates a new plugin loader. The loader carries no instance state and is
     * safe to share across commands within a single CLI run.
     */
    public PluginLoader() {
        // Intentionally empty; PluginLoader is stateless.
    }

    /**
     * Loads all {@link TestDiscovery} providers registered via
     * {@link ServiceLoader}, configures each one with {@code config}, and
     * returns them in registration order.
     *
     * <p>
     * The returned providers are open resources. Callers must close them
     * through {@link #closeAll(List)} in a {@code finally} block to release
     * any per-provider resources (file handles, native processes, etc.).
     * </p>
     *
     * <p>
     * Time complexity is {@code O(p)} in the number of providers; the
     * ServiceLoader lookup itself is dominated by classpath scanning.
     * </p>
     *
     * @param config run-time configuration forwarded to every provider via
     *               {@link TestDiscovery#configure}; must not be {@code null}
     * @return non-empty list of configured providers in registration order
     * @throws IllegalStateException if no providers are found on the classpath,
     *                               or if two providers share the same
     *                               {@link TestDiscovery#pluginId()}
     */
    @SuppressWarnings("PMD.CloseResource") // callers own the lifecycle and must close via closeAll()
    public List<TestDiscovery> loadProviders(TestDiscoveryConfig config) {
        List<TestDiscovery> providers = new ArrayList<>();
        for (TestDiscovery provider : ServiceLoader.load(TestDiscovery.class)) {
            provider.configure(config);
            providers.add(provider);
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "No TestDiscovery providers found on the classpath. "
                    + "Ensure at least one provider JAR ships the service registration file "
                    + "META-INF/services/org.egothor.methodatlas.api.TestDiscovery.");
        }
        requireUniqueDiscoveryIds(providers);
        return providers;
    }

    /**
     * Loads all {@link SourcePatcher} providers registered via
     * {@link ServiceLoader}, configures each one with {@code config}, and
     * returns them in registration order.
     *
     * <p>
     * Unlike {@link #loadProviders}, returning an empty list is legitimate:
     * languages that do not support source write-back (such as TypeScript or
     * Python) ship no patcher.
     * </p>
     *
     * @param config run-time configuration forwarded to every patcher via
     *               {@link SourcePatcher#configure}; must not be {@code null}
     * @return possibly-empty list of configured patchers in registration order
     * @throws IllegalStateException if two patchers share the same
     *                               {@link SourcePatcher#pluginId()}
     */
    public List<SourcePatcher> loadPatchers(TestDiscoveryConfig config) {
        List<SourcePatcher> patchers = new ArrayList<>();
        for (SourcePatcher patcher : ServiceLoader.load(SourcePatcher.class)) {
            patcher.configure(config);
            patchers.add(patcher);
        }
        requireUniquePatcherIds(patchers);
        return patchers;
    }

    /**
     * Closes every provider in the list, logging any {@link IOException} at
     * {@link Level#FINE} and continuing so that all providers are attempted.
     *
     * <p>
     * This method never throws: a provider whose {@code close} fails leaves
     * its resources in an indeterminate state, but the orchestration layer
     * always exits cleanly. Failures are observable through the FINE-level
     * log.
     * </p>
     *
     * @param providers list of providers to close; must not be {@code null}
     */
    @SuppressWarnings("PMD.CloseResource") // this method IS the close mechanism; p.close() is called explicitly
    public void closeAll(List<TestDiscovery> providers) {
        for (TestDiscovery p : providers) {
            try {
                p.close();
            } catch (IOException e) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "Failed to close provider " + p.pluginId(), e);
                }
            }
        }
    }

    /**
     * Verifies that every {@link TestDiscovery} provider in the list has a
     * unique {@link TestDiscovery#pluginId()}.
     *
     * <p>
     * This method is {@code static} because it is a pure validation with no
     * instance dependencies — test code calls it directly with handcrafted
     * provider lists, and the instance loader calls it after a
     * {@code ServiceLoader} sweep. Time complexity is {@code O(p)} in the
     * number of providers.
     * </p>
     *
     * @param providers list of providers to validate; must not be {@code null}
     * @throws IllegalStateException if two or more providers share the same id
     */
    @SuppressWarnings("PMD.CloseResource") // providers are owned by the caller; this method does not close them
    public static void requireUniqueDiscoveryIds(List<TestDiscovery> providers) {
        Set<String> seen = new LinkedHashSet<>();
        for (TestDiscovery p : providers) {
            String id = p.pluginId();
            if (!seen.add(id)) {
                throw new IllegalStateException(
                        "Duplicate TestDiscovery plugin ID \"" + id + "\": two or more "
                        + "registered providers claim the same pluginId(). "
                        + "Each provider must declare a unique identifier.");
            }
        }
    }

    /**
     * Verifies that every {@link SourcePatcher} in the list has a unique
     * {@link SourcePatcher#pluginId()}.
     *
     * @param patchers list of patchers to validate; must not be {@code null}
     * @throws IllegalStateException if two or more patchers share the same id
     */
    public static void requireUniquePatcherIds(List<SourcePatcher> patchers) {
        Set<String> seen = new LinkedHashSet<>();
        for (SourcePatcher p : patchers) {
            String id = p.pluginId();
            if (!seen.add(id)) {
                throw new IllegalStateException(
                        "Duplicate SourcePatcher plugin ID \"" + id + "\": two or more "
                        + "registered patchers claim the same pluginId(). "
                        + "Each patcher must declare a unique identifier.");
            }
        }
    }
}
