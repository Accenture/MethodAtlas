package org.egothor.methodatlas.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Default implementation of {@link AiSuggestionEngine} that coordinates
 * provider selection and taxonomy loading for AI-based security classification.
 *
 * <p>
 * This implementation acts as the primary orchestration layer between the
 * command-line application and the provider-specific AI client subsystem. It
 * resolves the effective {@link AiProviderClient} through
 * {@link AiProviderFactory}, loads the taxonomy text used to guide
 * classification, and delegates class-level analysis requests to the selected
 * provider client.
 * </p>
 *
 * <h2>Responsibilities</h2>
 *
 * <ul>
 * <li>creating the effective provider client from {@link AiOptions}</li>
 * <li>loading taxonomy text from a configured file or from the selected
 * built-in taxonomy mode</li>
 * <li>delegating class analysis requests to the provider client</li>
 * <li>presenting a provider-independent {@link AiSuggestionEngine} contract to
 * higher-level callers</li>
 * </ul>
 *
 * <p>
 * Instances of this class are immutable after construction and are intended to
 * be created once per application run.
 * </p>
 *
 * @see AiSuggestionEngine
 * @see AiProviderFactory
 * @see AiProviderClient
 * @see AiOptions.TaxonomyMode
 */
public final class AiSuggestionEngineImpl implements AiSuggestionEngine {

    private static final Logger LOG = Logger.getLogger(AiSuggestionEngineImpl.class.getName());

    /** Sentinel token count used when the provider does not report token usage. */
    private static final int UNKNOWN_TOKEN_COUNT = -1;

    private final AiProviderClient client;
    private final String taxonomyText;
    private final AiOptions options;
    /**
     * Optional callback invoked after each successful provider call so observers
     * (e.g. the evidence-pack archive) can record provenance. Set via
     * {@link #setResponseListener(AiResponseListener)}; {@code null} when no
     * listener has been registered.
     */
    private AiResponseListener responseListener;

    /**
     * Creates a new AI suggestion engine using the supplied runtime options.
     *
     * <p>Rate-limit events are silently discarded by this constructor.  Use
     * {@link #AiSuggestionEngineImpl(AiOptions, RateLimitListener)} when the
     * caller needs to be informed of HTTP&nbsp;429 pauses.</p>
     *
     * <p>
     * During construction, the implementation resolves the effective provider
     * client and loads the taxonomy text that will be supplied to the AI provider
     * for subsequent classification requests. The taxonomy is taken from an
     * external file when configured; otherwise, the built-in taxonomy selected by
     * {@link AiOptions#taxonomyMode()} is used.
     * </p>
     *
     * @param options AI runtime configuration controlling provider selection,
     *                taxonomy loading, and request behavior
     *
     * @throws AiSuggestionException if provider initialization fails or if the
     *                               configured taxonomy cannot be loaded
     * @see #AiSuggestionEngineImpl(AiOptions, RateLimitListener)
     */
    public AiSuggestionEngineImpl(AiOptions options) throws AiSuggestionException {
        this.client = AiProviderFactory.create(options);
        this.taxonomyText = loadTaxonomy(options);
        this.options = options;
    }

    /**
     * Creates a new AI suggestion engine that notifies
     * {@code rateLimitListener} before each rate-limit sleep.
     *
     * <p>
     * During construction, the implementation resolves the effective provider
     * client and loads the taxonomy text that will be supplied to the AI provider
     * for subsequent classification requests. The taxonomy is taken from an
     * external file when configured; otherwise, the built-in taxonomy selected by
     * {@link AiOptions#taxonomyMode()} is used.
     * </p>
     *
     * @param options             AI runtime configuration controlling provider
     *                            selection, taxonomy loading, and request behavior
     * @param rateLimitListener   callback invoked before each HTTP&nbsp;429
     *                            pause; must not be {@code null}
     *
     * @throws AiSuggestionException if provider initialization fails or if the
     *                               configured taxonomy cannot be loaded
     * @see RateLimitListener
     */
    public AiSuggestionEngineImpl(AiOptions options, RateLimitListener rateLimitListener)
            throws AiSuggestionException {
        this.client = AiProviderFactory.create(options, rateLimitListener);
        this.taxonomyText = loadTaxonomy(options);
        this.options = options;
    }

    /**
     * Registers a listener that the engine will notify after each successful
     * AI round-trip. Replaces any previously registered listener.
     *
     * @param listener listener to invoke; may be {@code null} to clear a
     *                 previously registered listener
     */
    @Override
    public void setResponseListener(AiResponseListener listener) {
        this.responseListener = listener;
    }

    /**
     * Requests AI-generated security classification for a single parsed test class.
     *
     * <p>
     * The method delegates directly to the configured {@link AiProviderClient},
     * supplying the fully qualified class name, the complete class source, and the
     * taxonomy text loaded at engine initialization time.
     * </p>
     *
     * <p>
     * On success, any listener registered via
     * {@link #setResponseListener(AiResponseListener)} is notified with the
     * rendered prompt and the AI result.
     * </p>
     *
     * @param fileStem      file stem of the source file; forwarded for
     *                      provenance (e.g. manual-workflow work-file naming),
     *                      not used in classification
     * @param fqcn          fully qualified class name of the analyzed test class
     * @param classSource   complete source code of the class to analyze
     * @param targetMethods deterministically extracted JUnit test methods that must
     *                      be classified
     * @return normalized AI classification result for the class and its methods
     *
     * @throws AiSuggestionException if the provider fails to analyze the class or
     *                               returns an invalid response
     *
     * @see AiClassSuggestion
     * @see AiProviderClient#suggestForClass(String, String)
     */
    @Override
    public AiClassSuggestion suggestForClass(String fileStem, String fqcn, String classSource,
            List<PromptBuilder.TargetMethod> targetMethods) throws AiSuggestionException {
        String prompt = PromptBuilder.build(fqcn, classSource, taxonomyText, targetMethods, options.confidence());
        AiClassSuggestion result = client.suggestForClass(fqcn, prompt);
        notifyResponseListener(fqcn, prompt, result);
        return result;
    }

    /**
     * Forwards the rendered prompt and a JSON serialisation of the AI result
     * to the registered listener, if any.
     *
     * <p>
     * The prompt is the exact string already submitted to the provider by
     * {@link #suggestForClass(String, String, String, List)}; it is threaded
     * in rather than rebuilt so the listener records ground truth and the
     * prompt is assembled only once per call.
     * </p>
     *
     * @param fqcn   fully qualified class name passed to the provider
     * @param prompt rendered prompt that was submitted to the provider
     * @param result normalised classification result returned by the provider
     */
    private void notifyResponseListener(String fqcn, String prompt, AiClassSuggestion result) {
        AiResponseListener listener = this.responseListener;
        if (listener == null) {
            return;
        }
        String response;
        try {
            response = JsonMapper.builder().build().writeValueAsString(result);
        } catch (JsonProcessingException e) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Failed to serialize AI response for archive", e);
            }
            response = "";
        }
        listener.onResponse(null, fqcn, prompt, response,
                options.modelName(), UNKNOWN_TOKEN_COUNT, UNKNOWN_TOKEN_COUNT);
    }

    /**
     * Loads the taxonomy text used to guide AI classification.
     *
     * <p>
     * Resolution order:
     * </p>
     * <ol>
     * <li>If an external taxonomy file is configured, its contents are used.</li>
     * <li>Otherwise, the built-in taxonomy selected by
     * {@link AiOptions#taxonomyMode()} is used.</li>
     * </ol>
     *
     * @param options AI runtime configuration
     * @return taxonomy text to be supplied to the AI provider
     *
     * @throws AiSuggestionException if an external taxonomy file is configured but
     *                               cannot be read successfully
     *
     * @see DefaultSecurityTaxonomy#text()
     * @see OptimizedSecurityTaxonomy#text()
     */
    private static String loadTaxonomy(AiOptions options) throws AiSuggestionException {
        if (options.taxonomyFile() != null) {
            try {
                return Files.readString(options.taxonomyFile());
            } catch (IOException e) {
                throw new AiSuggestionException("Failed to read taxonomy file: " + options.taxonomyFile(), e);
            }
        }

        return switch (options.taxonomyMode()) {
            case DEFAULT -> DefaultSecurityTaxonomy.text();
            case OPTIMIZED -> OptimizedSecurityTaxonomy.text();
        };
    }
}
