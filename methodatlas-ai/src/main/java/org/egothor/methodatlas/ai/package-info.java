/**
 * AI integration layer for MethodAtlas providing automated security
 * classification of JUnit test methods.
 *
 * <p>
 * This package contains the infrastructure required to obtain AI-assisted
 * suggestions for security tagging of JUnit 5 tests. The subsystem analyzes
 * complete test classes, submits classification prompts to an AI provider, and
 * converts the returned results into structured suggestions that can be
 * consumed by the main application.
 * </p>
 *
 * <h2>Architecture Overview</h2>
 *
 * <p>
 * The AI subsystem follows a layered design:
 * </p>
 *
 * <ul>
 * <li><b>Engine layer</b> –
 * {@link org.egothor.methodatlas.ai.AiSuggestionEngine} orchestrates provider
 * communication and taxonomy handling.</li>
 *
 * <li><b>Provider layer</b> – implementations of
 * {@link org.egothor.methodatlas.ai.AiProviderClient} integrate with specific
 * AI services such as Ollama, OpenAI-compatible APIs, or Anthropic.</li>
 *
 * <li><b>Prompt construction</b> –
 * {@link org.egothor.methodatlas.ai.PromptBuilder} builds the prompt that
 * instructs the model how to perform security classification.</li>
 *
 * <li><b>Taxonomy definition</b> –
 * {@link org.egothor.methodatlas.ai.DefaultSecurityTaxonomy} and
 * {@link org.egothor.methodatlas.ai.OptimizedSecurityTaxonomy} define the
 * controlled vocabulary used for tagging.</li>
 *
 * <li><b>Result normalization</b> – AI responses are converted into the
 * structured domain model ({@link org.egothor.methodatlas.ai.AiClassSuggestion}
 * and {@link org.egothor.methodatlas.ai.AiMethodSuggestion}).</li>
 * </ul>
 *
 * <h2>Security Considerations</h2>
 *
 * <p>
 * Source code analyzed by the AI subsystem may contain sensitive information.
 * For environments where external transmission of code is undesirable, the
 * subsystem supports local inference through
 * {@link org.egothor.methodatlas.ai.OllamaClient}.
 * </p>
 *
 * <h2>Deterministic Output</h2>
 *
 * <p>
 * The subsystem is designed to obtain deterministic, machine-readable output
 * from AI models. Prompts enforce strict JSON responses and classification
 * decisions are constrained by a controlled taxonomy.
 * </p>
 *
 * <h2>Extensibility</h2>
 *
 * <p>
 * Additional AI providers can be integrated by implementing
 * {@link org.egothor.methodatlas.ai.AiProviderClient} and registering the
 * implementation in {@link org.egothor.methodatlas.ai.AiProviderFactory}.
 * </p>
 *
 * @since 1.0.1
 */
package org.egothor.methodatlas.ai;