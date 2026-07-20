package org.egothor.methodatlas.gui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.egothor.methodatlas.gui.model.AnalysisModel;
import org.egothor.methodatlas.gui.model.AppSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link AnalysisService}.
 *
 * <p>
 * The service is a {@link javax.swing.SwingWorker}; discovery providers are
 * located through {@link java.util.ServiceLoader}.  {@link FakeTestDiscovery}
 * is registered via {@code META-INF/services} so these tests exercise the real
 * two-phase orchestration end to end without a live AI provider (the default
 * profile has AI disabled) and without any bundled language plugin.
 * </p>
 */
class AnalysisServiceTest {

    private static final long TIMEOUT_MS = 5_000L;

    @BeforeEach
    @AfterEach
    void resetProvider() {
        FakeTestDiscovery.reset();
    }

    @Test
    void availablePluginIds_includesRegisteredProvider() {
        assertTrue(AnalysisService.availablePluginIds().contains("fake"),
                "ServiceLoader should discover the test provider");
    }

    @Test
    void discovery_populatesModel_andReachesDoneWithAiDisabled() throws Exception {
        FakeTestDiscovery.methods = List.of(
                FakeTestDiscovery.method("com.acme.FooTest", "test_a"),
                FakeTestDiscovery.method("com.acme.FooTest", "test_b"));

        AppSettings settings = new AppSettings(); // AI disabled by default
        settings.setEnabledPlugins(List.of("fake")); // isolate from any other classpath provider
        AnalysisModel model = new AnalysisModel();

        AnalysisService service = new AnalysisService(settings, Path.of("."), model);
        service.execute();
        service.get(); // wait for doInBackground to finish

        awaitStatus(model, AnalysisModel.Status.DONE);

        assertEquals(2, (int) onEdt(model::getTotalMethodCount), "both methods should be modelled");
        assertEquals(1, (int) onEdt(model::getClassCount));
        assertTrue(onEdt(model::getStatusMessage).contains("AI disabled"),
                "status message should note AI was disabled");
        assertTrue(FakeTestDiscovery.closed, "provider should be closed after the run");
    }

    @Test
    void discoveryFailure_propagates_andClosesProviders() {
        FakeTestDiscovery.throwOnDiscover = true;

        AppSettings settings = new AppSettings();
        settings.setEnabledPlugins(List.of("fake")); // isolate from any other classpath provider
        AnalysisService service =
                new AnalysisService(settings, Path.of("."), new AnalysisModel());
        service.execute();

        ExecutionException thrown = assertThrows(ExecutionException.class, service::get);
        assertInstanceOf(IOException.class, thrown.getCause(),
                "discovery IOException should propagate through the worker");
        assertTrue(FakeTestDiscovery.closed, "providers must be closed even when discovery fails");
    }

    // ── EDT helpers ───────────────────────────────────────────────────────

    private static void awaitStatus(AnalysisModel model, AnalysisModel.Status target)
            throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            flushEdt();
            if (onEdt(model::getStatus) == target) {
                return;
            }
            Thread.sleep(25);
        }
        fail("analysis did not reach " + target + " within " + TIMEOUT_MS
                + " ms; last status=" + onEdt(model::getStatus));
    }

    private static <T> T onEdt(Supplier<T> supplier) throws Exception {
        Object[] box = new Object[1];
        SwingUtilities.invokeAndWait(() -> box[0] = supplier.get());
        @SuppressWarnings("unchecked")
        T value = (T) box[0];
        return value;
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }
}
