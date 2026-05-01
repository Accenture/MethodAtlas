package org.egothor.methodatlas.gui.service;

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiProvider;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.egothor.methodatlas.ai.AiSuggestionException;
import org.egothor.methodatlas.ai.PromptBuilder;
import org.egothor.methodatlas.ai.RateLimitListener;
import org.egothor.methodatlas.api.DiscoveredMethod;
import org.egothor.methodatlas.api.TestDiscovery;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.gui.model.AiProfile;
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
 * {@link SwingWorker} that orchestrates test-source discovery and optional
 * AI enrichment for a single directory root.
 *
 * <h2>Two-phase design</h2>
 * <ol>
 *   <li><strong>Phase 1 — Discovery.</strong>  All {@link TestDiscovery}
 *       providers that pass the {@link AppSettings#getEnabledPlugins()}
 *       filter are invoked sequentially against the root directory.  Each
 *       discovered method is published immediately via {@link #publish} so
 *       that the results tree starts populating before any AI call is
 *       made.</li>
 *   <li><strong>Phase 2 — AI enrichment</strong> (optional, controlled by
 *       {@link AiProfile#isEnabled()} on the active profile).  The AI engine is queried once
 *       per class rather than once per method, which reduces round-trips and
 *       lets the model reason about the class as a whole.  Progress is
 *       reported as each class completes.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>All {@link Update} messages are published from the worker thread via
 * {@link #publish} and consumed on the Swing Event Dispatch Thread via
 * {@link #process}.  Callers must not call {@link AnalysisModel} mutation
 * methods from any other thread.</p>
 *
 * @see TestDiscovery
 * @see AnalysisModel
 * @see AppSettings
 */
public final class AnalysisService extends SwingWorker<Void, AnalysisService.Update> {

    private static final Logger LOG = Logger.getLogger(AnalysisService.class.getName());

    // ── Fields ────────────────────────────────────────────────────────────

    private final AppSettings settings;
    private final Path root;
    private final AnalysisModel model;

    // ── Internal update messages ──────────────────────────────────────────

    /**
     * Sealed base type for all progress messages exchanged between the
     * worker thread and the EDT.
     *
     * <p>Instances are produced on the worker thread via
     * {@link SwingWorker#publish publish} and consumed on the EDT via
     * {@link SwingWorker#process process}.  The sealed hierarchy allows
     * exhaustive pattern-matching in {@code process} without a default
     * branch.</p>
     *
     * @see MethodFound
     * @see AiUpdate
     * @see AiClassDone
     * @see StatusChange
     * @see ProgressUpdate
     */
    public sealed interface Update
            permits MethodFound, AiUpdate, AiClassDone, StatusChange, ProgressUpdate {}

    /**
     * Signals that a new test method has been discovered during Phase 1.
     *
     * @param entry the newly discovered method, pre-wrapped in a
     *              {@link MethodEntry} with no AI suggestion yet
     */
    public record MethodFound(MethodEntry entry) implements Update {}

    /**
     * Carries an AI suggestion for a single method within a class that was
     * processed during Phase 2.
     *
     * <p>Multiple {@code AiUpdate} messages are published for the same class
     * (one per method), all before the corresponding {@link AiClassDone}.</p>
     *
     * @param fqcn       fully-qualified name of the class that contains the
     *                   method
     * @param method     simple name of the method the suggestion applies to
     * @param suggestion the AI-generated suggestion; may be {@code null}
     *                   if the engine returned no data for this method
     */
    public record AiUpdate(String fqcn, String method, AiMethodSuggestion suggestion) implements Update {}

    /**
     * Signals that the AI engine has finished processing one class in
     * Phase 2.
     *
     * <p>This message is always published after all {@link AiUpdate} messages
     * for the same class, including when the class was skipped because its
     * source was unavailable, and including when the AI call failed.</p>
     *
     * @param fqcn        fully-qualified name of the completed class
     * @param methodCount number of methods the class contains
     * @param durationMs  wall-clock time spent on the AI call, in
     *                    milliseconds; {@code 0} if no call was made
     * @param hadError    {@code true} if the AI call threw an
     *                    {@link AiSuggestionException}
     */
    public record AiClassDone(
            String fqcn, int methodCount, long durationMs, boolean hadError)
            implements Update {}

    /**
     * Signals a change to the human-readable status message and the
     * lifecycle state.
     *
     * @param status  new lifecycle state; never {@code null}
     * @param message human-readable description of the current operation;
     *                never {@code null}
     */
    public record StatusChange(AnalysisModel.Status status, String message) implements Update {}

    /**
     * Reports progress through the AI enrichment phase.
     *
     * <p>Published once at the start of each class, before any
     * {@link AiUpdate} messages for that class.</p>
     *
     * @param current      1-based index of the class now being processed
     * @param total        total number of classes to process in this run
     * @param currentClass fully-qualified name of the class now being
     *                     processed; never {@code null}
     */
    public record ProgressUpdate(int current, int total, String currentClass) implements Update {}

    /**
     * Constructs a new analysis service for the given root directory.
     *
     * <p>Call {@link #execute()} to start the background work.  The
     * supplied {@code model} is populated entirely on the EDT via
     * {@link #process}; callers must not read from it on the worker
     * thread.</p>
     *
     * @param settings current application settings, including AI options
     *                 and the enabled-plugin filter; must not be
     *                 {@code null}
     * @param root     directory root to scan for test sources; must be an
     *                 existing directory; must not be {@code null}
     * @param model    model to populate with discovered methods and AI
     *                 suggestions; must not be {@code null}
     */
    public AnalysisService(AppSettings settings, Path root, AnalysisModel model) {
        super();
        this.settings = settings;
        this.root = root;
        this.model = model;
    }

    // ── SwingWorker ───────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Runs Phase 1 (discovery) followed by optional Phase 2 (AI
     * enrichment) as described in the class-level documentation.</p>
     *
     * @throws IllegalStateException if no {@link TestDiscovery} providers
     *         are found on the classpath, or if all providers were excluded
     *         by the enabled-plugin filter
     * @throws Exception if an unexpected error occurs during file traversal
     */
    @Override
    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidInstantiatingObjectsInLoops"})
    protected Void doInBackground() throws Exception {
        publish(new StatusChange(AnalysisModel.Status.SCANNING, "Scanning " + root + " …"));

        TestDiscoveryConfig discoveryConfig = new TestDiscoveryConfig(
                buildFlatSuffixes(settings),
                Set.copyOf(settings.getTestAnnotations()),
                Map.of());

        List<TestDiscovery> providers = loadProviders(discoveryConfig, settings.getEnabledPlugins());
        Map<String, List<DiscoveredMethod>> byClass = new LinkedHashMap<>();

        try {
            for (TestDiscovery provider : providers) {
                if (isCancelled()) { break; }
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

        if (!settings.getActiveProfile().isEnabled()) {
            publish(new StatusChange(AnalysisModel.Status.DONE,
                    "Done — " + totalMethods + " method(s), AI disabled"));
            return null;
        }

        runAiPhase(byClass);
        return null;
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private void runAiPhase(Map<String, List<DiscoveredMethod>> byClass) {
        RateLimitListener rateLimitListener = (waitSeconds, attempt, max) ->
                publish(new StatusChange(AnalysisModel.Status.AI_RUNNING,
                        "Rate limit — waiting " + waitSeconds + "s (retry " + attempt + "/" + max + ")…"));

        AiSuggestionEngine engine;
        try {
            engine = new AiSuggestionEngineImpl(buildAiOptions(), rateLimitListener);
        } catch (AiSuggestionException e) {
            LOG.log(Level.WARNING, "AI engine initialisation failed", e);
            publish(new StatusChange(AnalysisModel.Status.DONE,
                    "Done (AI unavailable: " + e.getMessage() + ")"));
            return;
        }

        publish(new StatusChange(AnalysisModel.Status.AI_RUNNING, "AI enrichment …"));

        int idx = 0;
        int total = byClass.size();
        int securityRelevantCount = 0;

        for (Map.Entry<String, List<DiscoveredMethod>> classEntry : byClass.entrySet()) {
            if (isCancelled()) { break; }
            idx++;
            String fqcn = classEntry.getKey();
            List<DiscoveredMethod> classMethods = classEntry.getValue();

            publish(new ProgressUpdate(idx, total, fqcn));
            publish(new StatusChange(AnalysisModel.Status.AI_RUNNING,
                    "AI enrichment [" + idx + "/" + total + "] — " + simpleName(fqcn)));

            String classSource = classMethods.get(0).sourceContent().get().orElse(null);
            if (classSource == null) {
                publish(new AiClassDone(fqcn, classMethods.size(), 0, false));
                continue;
            }

            String fileStem = classMethods.get(0).fileStem();
            List<PromptBuilder.TargetMethod> targets = classMethods.stream()
                    .map(m -> new PromptBuilder.TargetMethod(
                            m.method(),
                            m.beginLine() > 0 ? m.beginLine() : null,
                            m.endLine() > 0 ? m.endLine() : null))
                    .toList();

            long classStart = System.currentTimeMillis();
            boolean hadError = false;
            try {
                AiClassSuggestion classSugg = engine.suggestForClass(fileStem, fqcn, classSource, targets);
                if (classSugg != null && classSugg.methods() != null) {
                    for (AiMethodSuggestion methodSugg : classSugg.methods()) {
                        publish(new AiUpdate(fqcn, methodSugg.methodName(), methodSugg));
                        if (methodSugg.securityRelevant()) { securityRelevantCount++; }
                    }
                }
            } catch (AiSuggestionException e) {
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING, "AI failed for class " + fqcn, e);
                }
                hadError = true;
            }
            publish(new AiClassDone(fqcn, classMethods.size(),
                    System.currentTimeMillis() - classStart, hadError));
        }

        int done = byClass.values().stream().mapToInt(List::size).sum();
        String doneMsg = "Done — " + done + " method(s) analysed";
        if (securityRelevantCount > 0) {
            doneMsg += ", " + securityRelevantCount + " security-relevant";
        }
        publish(new StatusChange(AnalysisModel.Status.DONE, doneMsg));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Dispatches each {@link Update} to the appropriate
     * {@link AnalysisModel} mutator.  Runs on the EDT.</p>
     */
    @Override
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops") // AiClassResult record created per update chunk — unavoidable
    protected void process(List<Update> chunks) {
        for (Update update : chunks) {
            switch (update) {
                case MethodFound f -> model.addEntry(f.entry());
                case AiUpdate a -> model.updateSuggestion(a.fqcn(), a.method(), a.suggestion());
                case AiClassDone d -> model.addAiClassResult(
                        new AnalysisModel.AiClassResult(d.fqcn(), d.methodCount(), d.durationMs(), d.hadError()));
                case StatusChange s -> {
                    model.setStatus(s.status());
                    model.setStatusMessage(s.message());
                }
                case ProgressUpdate p -> {
                    model.setProgress(p.current(), p.total());
                    model.setCurrentAiClass(p.currentClass());
                }
            }
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────

    /**
     * Returns the plugin IDs of all {@link TestDiscovery} providers
     * currently available on the classpath.
     *
     * <p>The IDs are returned in the order in which
     * {@link ServiceLoader} discovers them.  The list is empty only when
     * no provider JARs are present on the classpath, which is an
     * installation error.</p>
     *
     * <p>This method is called by the settings dialog to populate the
     * plugin selection UI; it does not alter any provider state.</p>
     *
     * @return mutable list of plugin IDs; never {@code null} but may be
     *         empty if no providers are found
     */
    public static List<String> availablePluginIds() {
        List<String> ids = new ArrayList<>();
        ServiceLoader.load(TestDiscovery.class).forEach(p -> ids.add(p.pluginId()));
        return ids;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private AiOptions buildAiOptions() {
        AiProfile profile = settings.getActiveProfile();
        AiProvider provider = AiProvider.valueOf(profile.getProvider());
        AiOptions.Builder builder = AiOptions.builder()
                .enabled(true)
                .provider(provider)
                .modelName(profile.getModel())
                .timeout(Duration.ofSeconds(profile.getTimeoutSeconds()))
                .maxRetries(profile.getMaxRetries())
                .confidence(profile.isConfidence())
                .apiVersion(profile.getApiVersion());

        String key = profile.getApiKey();
        if (key != null && !key.isBlank()) {
            builder.apiKey(key);
        }
        String url = profile.getBaseUrl();
        if (url != null && !url.isBlank()) {
            builder.baseUrl(url);
        }
        return builder.build();
    }

    /**
     * Converts the per-plugin suffix map from {@link AppSettings} into the
     * flat {@code "pluginId:suffix"} format expected by
     * {@link org.egothor.methodatlas.api.TestDiscoveryConfig}.
     *
     * <p>An empty map causes an empty list to be returned, which means every
     * loaded plugin falls back to its own built-in default file suffixes.</p>
     *
     * @param settings current application settings; must not be {@code null}
     * @return flat suffix list in {@code "pluginId:suffix"} format; never
     *         {@code null}
     */
    private static List<String> buildFlatSuffixes(AppSettings settings) {
        List<String> result = new ArrayList<>();
        settings.getPluginSuffixes().forEach((pluginId, suffixes) ->
                suffixes.forEach(s -> result.add(pluginId + ":" + s)));
        return result;
    }

    /**
     * Loads all {@link TestDiscovery} providers from the classpath,
     * filtering by {@code enabled} when the list is non-empty.
     *
     * @param config  discovery configuration passed to each provider
     * @param enabled plugin IDs to include; an empty list means all
     *                available providers are used
     * @return configured provider list; never empty
     * @throws IllegalStateException if the resulting list would be empty
     *         (no providers found, or all were filtered out)
     */
    private static List<TestDiscovery> loadProviders(TestDiscoveryConfig config, List<String> enabled) {
        List<TestDiscovery> providers = new ArrayList<>();
        ServiceLoader.load(TestDiscovery.class).forEach(p -> {
            if (enabled.isEmpty() || enabled.contains(p.pluginId())) {
                p.configure(config);
                providers.add(p);
            }
        });
        if (providers.isEmpty()) {
            throw new IllegalStateException("No TestDiscovery providers found on the classpath.");
        }
        return providers;
    }

    @SuppressWarnings("PMD.CloseResource")
    private static void closeAll(List<TestDiscovery> providers) {
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

    /** Returns the unqualified class name extracted from a fully-qualified name. */
    private static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }
}
