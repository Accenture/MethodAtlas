package org.egothor.methodatlas.gui.service;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiProvider;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.egothor.methodatlas.ai.AiSuggestionException;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.gui.model.AnalysisModel;
import org.egothor.methodatlas.gui.model.AppSettings;
import org.egothor.methodatlas.gui.model.MethodEntry;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background worker that runs test-source discovery and optional AI enrichment.
 *
 * <p>Phase 1 discovers all test methods and publishes them immediately so the
 * tree populates without waiting for AI. Phase 2 queries the AI engine per
 * class and fires update events on the model.</p>
 */
public final class AnalysisService extends SwingWorker<Void, AnalysisService.Update> {

    private static final Logger LOG = Logger.getLogger(AnalysisService.class.getName());

    // ── Internal update messages ──────────────────────────────────────────

    /** Sealed hierarchy of progress messages published to the EDT. */
    public sealed interface Update
            permits MethodFound, AiUpdate, StatusChange, ProgressUpdate {}

    /** A new method was discovered. */
    public record MethodFound(MethodEntry entry) implements Update {}

    /** AI suggestion arrived for an existing entry. */
    public record AiUpdate(String fqcn, String method, AiMethodSuggestion suggestion) implements Update {}

    /** Status message changed. */
    public record StatusChange(AnalysisModel.Status status, String message) implements Update {}

    /** AI phase progress counter updated. */
    public record ProgressUpdate(int current, int total) implements Update {}

    // ── Fields ────────────────────────────────────────────────────────────

    private final AppSettings settings;
    private final Path root;
    private final AnalysisModel model;

    /**
     * @param settings current application settings
     * @param root     directory root to scan
     * @param model    model to populate (EDT updates only)
     */
    public AnalysisService(AppSettings settings, Path root, AnalysisModel model) {
        this.settings = settings;
        this.root = root;
        this.model = model;
    }

    // ── SwingWorker ───────────────────────────────────────────────────────

    @Override
    protected Void doInBackground() throws Exception {
        publish(new StatusChange(AnalysisModel.Status.SCANNING, "Scanning " + root + " …"));

        TestDiscoveryConfig discoveryConfig = new TestDiscoveryConfig(
                settings.getFileSuffixes(),
                Set.copyOf(settings.getTestAnnotations()),
                Map.of());

        List<TestDiscovery> providers = loadProviders(discoveryConfig);
        Map<String, List<DiscoveredMethod>> byClass = new LinkedHashMap<>();

        try {
            for (TestDiscovery provider : providers) {
                if (isCancelled()) break;
                provider.discover(root).forEach(m -> {
                    byClass.computeIfAbsent(m.fqcn(), k -> new ArrayList<>()).add(m);
                    publish(new MethodFound(new MethodEntry(m, null)));
                });
            }
        } finally {
            closeAll(providers);
        }

        if (isCancelled()) {
            publish(new StatusChange(AnalysisModel.Status.IDLE, "Cancelled"));
            return null;
        }

        int totalMethods = byClass.values().stream().mapToInt(List::size).sum();
        publish(new StatusChange(AnalysisModel.Status.SCANNING,
                "Found " + totalMethods + " method(s) in " + byClass.size() + " class(es)"));

        if (!settings.isAiEnabled()) {
            publish(new StatusChange(AnalysisModel.Status.DONE,
                    "Done — " + totalMethods + " method(s), AI disabled"));
            return null;
        }

        runAiPhase(byClass);
        return null;
    }

    private void runAiPhase(Map<String, List<DiscoveredMethod>> byClass) {
        AiSuggestionEngine engine;
        try {
            engine = new AiSuggestionEngineImpl(buildAiOptions());
        } catch (AiSuggestionException e) {
            LOG.log(Level.WARNING, "AI engine initialisation failed", e);
            publish(new StatusChange(AnalysisModel.Status.DONE,
                    "Done (AI unavailable: " + e.getMessage() + ")"));
            return;
        }

        publish(new StatusChange(AnalysisModel.Status.AI_RUNNING, "AI enrichment …"));

        int idx = 0;
        int total = byClass.size();

        for (Map.Entry<String, List<DiscoveredMethod>> classEntry : byClass.entrySet()) {
            if (isCancelled()) break;
            idx++;
            String fqcn = classEntry.getKey();
            List<DiscoveredMethod> classMethods = classEntry.getValue();

            publish(new ProgressUpdate(idx, total));

            String classSource = classMethods.get(0).sourceContent().get().orElse(null);
            if (classSource == null) continue;

            String fileStem = classMethods.get(0).fileStem();
            List<PromptBuilder.TargetMethod> targets = classMethods.stream()
                    .map(m -> new PromptBuilder.TargetMethod(
                            m.method(),
                            m.beginLine() > 0 ? m.beginLine() : null,
                            m.endLine() > 0 ? m.endLine() : null))
                    .toList();

            try {
                AiClassSuggestion classSugg = engine.suggestForClass(fileStem, fqcn, classSource, targets);
                if (classSugg != null && classSugg.methods() != null) {
                    for (AiMethodSuggestion methodSugg : classSugg.methods()) {
                        publish(new AiUpdate(fqcn, methodSugg.methodName(), methodSugg));
                    }
                }
            } catch (AiSuggestionException e) {
                LOG.log(Level.WARNING, "AI failed for class " + fqcn, e);
            }
        }

        int done = byClass.values().stream().mapToInt(List::size).sum();
        publish(new StatusChange(AnalysisModel.Status.DONE,
                "Done — " + done + " method(s) analysed"));
    }

    @Override
    protected void process(List<Update> chunks) {
        for (Update update : chunks) {
            switch (update) {
                case MethodFound f -> model.addEntry(f.entry());
                case AiUpdate a -> model.updateSuggestion(a.fqcn(), a.method(), a.suggestion());
                case StatusChange s -> {
                    model.setStatus(s.status());
                    model.setStatusMessage(s.message());
                }
                case ProgressUpdate p -> model.setProgress(p.current(), p.total());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private AiOptions buildAiOptions() {
        AiProvider provider = AiProvider.valueOf(settings.getAiProvider());
        AiOptions.Builder builder = AiOptions.builder()
                .enabled(true)
                .provider(provider)
                .modelName(settings.getAiModel())
                .timeout(Duration.ofSeconds(settings.getAiTimeoutSeconds()))
                .maxRetries(settings.getAiMaxRetries())
                .confidence(settings.isAiConfidence())
                .apiVersion(settings.getAiApiVersion());

        String key = settings.getAiApiKey();
        if (key != null && !key.isBlank()) {
            builder.apiKey(key);
        }
        String url = settings.getAiBaseUrl();
        if (url != null && !url.isBlank()) {
            builder.baseUrl(url);
        }
        return builder.build();
    }

    private static List<TestDiscovery> loadProviders(TestDiscoveryConfig config) {
        List<TestDiscovery> providers = new ArrayList<>();
        ServiceLoader.load(TestDiscovery.class).forEach(p -> {
            p.configure(config);
            providers.add(p);
        });
        if (providers.isEmpty()) {
            throw new IllegalStateException("No TestDiscovery providers found on the classpath.");
        }
        return providers;
    }

    private static void closeAll(List<TestDiscovery> providers) {
        for (TestDiscovery p : providers) {
            try {
                p.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Failed to close provider " + p.pluginId(), e);
            }
        }
    }
}
