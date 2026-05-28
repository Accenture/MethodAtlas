/**
 * CLI command handler implementations for MethodAtlas.
 *
 * <p>
 * Each class in this package encapsulates one logical CLI mode and implements
 * the {@link org.egothor.methodatlas.command.Command} interface. The thin
 * routing layer in {@link org.egothor.methodatlas.MethodAtlasApp#run} selects
 * the appropriate command based on the parsed command-line flags and delegates
 * to it via {@link org.egothor.methodatlas.command.Command#execute}.
 * </p>
 *
 * <h2>Command handlers</h2>
 *
 * <ul>
 * <li>{@link org.egothor.methodatlas.command.DiffCommand} — {@code -diff}</li>
 * <li>{@link org.egothor.methodatlas.command.ScanCommand} — CSV / plain-text
 *     output (default mode)</li>
 * <li>{@link org.egothor.methodatlas.command.SarifCommand} — {@code -sarif}</li>
 * <li>{@link org.egothor.methodatlas.command.GitHubAnnotationsCommand} —
 *     {@code -github-annotations}</li>
 * <li>{@link org.egothor.methodatlas.command.ApplyTagsCommand} —
 *     {@code -apply-tags}</li>
 * <li>{@link org.egothor.methodatlas.command.ApplyTagsFromCsvCommand} —
 *     {@code -apply-tags-from-csv}</li>
 * <li>{@link org.egothor.methodatlas.command.ManualPrepareCommand} —
 *     {@code -manual-prepare}</li>
 * </ul>
 *
 * <h2>Shared infrastructure</h2>
 *
 * <p>
 * The shared infrastructure that the commands compose is decomposed into a
 * small set of focused collaborators:
 * </p>
 * <ul>
 *   <li>{@link org.egothor.methodatlas.command.PluginLoader} — resolves
 *       {@code TestDiscovery} and {@code SourcePatcher} providers via
 *       {@code ServiceLoader} and validates plugin-ID uniqueness.</li>
 *   <li>{@link org.egothor.methodatlas.command.OverrideLoader} — loads
 *       classification override YAML files into
 *       {@link org.egothor.methodatlas.emit.ClassificationOverride} instances.</li>
 *   <li>{@link org.egothor.methodatlas.command.ContentHasher} — produces
 *       SHA-256 class fingerprints and forward-slashed scan-root file
 *       prefixes; pure static helpers.</li>
 *   <li>{@link org.egothor.methodatlas.command.AiRuntimeBuilder} — builds
 *       the per-run AI engine, the result cache, and resolves taxonomy
 *       metadata.</li>
 *   <li>{@link org.egothor.methodatlas.command.ScanOrchestrator} — owns the
 *       scan-and-emit loop and the apply-tags helpers
 *       ({@code collectMethodsByFile}, {@code gatherAiSuggestionsForFile},
 *       {@code filterSink}). Constructed once per CLI run with an injected
 *       {@code PluginLoader}.</li>
 * </ul>
 *
 * <h2>Access conventions</h2>
 *
 * <p>
 * All classes in this package are declared {@code public} so that the routing
 * layer in {@link org.egothor.methodatlas.MethodAtlasApp} can instantiate
 * them. They are nonetheless considered internal implementation details:
 * callers outside this package should not depend on specific command classes;
 * they should interact only with the
 * {@link org.egothor.methodatlas.command.Command} interface.
 * </p>
 */
package org.egothor.methodatlas.command;
