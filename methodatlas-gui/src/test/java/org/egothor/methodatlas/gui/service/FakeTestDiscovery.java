package org.egothor.methodatlas.gui.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * Test-only {@link TestDiscovery} provider registered via
 * {@code META-INF/services} so {@link AnalysisService} discovers it through
 * {@link java.util.ServiceLoader} during headless behavioural tests.
 *
 * <p>
 * The provider is instantiated by {@code ServiceLoader} with the no-arg
 * constructor, so its behaviour is driven through static fields that each test
 * resets via {@link #reset()}.
 * </p>
 */
public final class FakeTestDiscovery implements TestDiscovery {

    static volatile List<DiscoveredMethod> methods = List.of();
    static volatile boolean throwOnDiscover;
    static volatile boolean closed;

    /** Required by {@link java.util.ServiceLoader}. */
    public FakeTestDiscovery() {
        // no-op
    }

    static void reset() {
        methods = List.of();
        throwOnDiscover = false;
        closed = false;
    }

    static DiscoveredMethod method(String fqcn, String name) {
        return new DiscoveredMethod(fqcn, name, 1, 3, 3, List.of(), null, null, fqcn,
                () -> Optional.empty());
    }

    @Override
    public String pluginId() {
        return "fake";
    }

    @Override
    public void configure(TestDiscoveryConfig config) {
        // no configuration needed
    }

    @Override
    public Stream<DiscoveredMethod> discover(Path root) throws IOException {
        if (throwOnDiscover) {
            throw new IOException("simulated discovery failure");
        }
        return methods.stream();
    }

    @Override
    public boolean hadErrors() {
        return false;
    }

    @Override
    public void close() {
        closed = true;
    }
}
