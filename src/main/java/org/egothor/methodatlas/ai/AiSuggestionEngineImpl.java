package org.egothor.methodatlas.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

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

    private final AiProviderClient client;
    private final String taxonomyText;

    /**
     * Creates a new AI suggestion engine using the supplied runtime options.
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
     */
    public AiSuggestionEngineImpl(AiOptions options) throws AiSuggestionException {
        this.client = AiProviderFactory.create(options);
        this.taxonomyText = loadTaxonomy(options);
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
     * @see AiProviderClient#suggestForClass(String, String, String)
     */
    @Override
    public AiClassSuggestion suggestForClass(String fqcn, String classSource,
            List<PromptBuilder.TargetMethod> targetMethods) throws AiSuggestionException {
        return client.suggestForClass(fqcn, classSource, taxonomyText, targetMethods);
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
