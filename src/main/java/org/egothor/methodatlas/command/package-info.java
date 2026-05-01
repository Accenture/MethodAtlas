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
 * {@link org.egothor.methodatlas.command.CommandSupport} is a utility class
 * that holds the static helpers shared by two or more command implementations:
 * provider/patcher loading, scan orchestration, AI suggestion resolution,
 * content hashing, and file-prefix computation.
 * </p>
 *
 * <h2>Access conventions</h2>
 *
 * <p>
 * All classes in this package are declared {@code public} so that the routing
 * layer in {@link org.egothor.methodatlas.MethodAtlasApp} can instantiate them.
 * They are nonetheless considered internal implementation details: callers
 * outside this package should not depend on specific command classes; they should
 * interact only with the {@link org.egothor.methodatlas.command.Command} interface
 * and with the {@code public} methods of
 * {@link org.egothor.methodatlas.command.CommandSupport}.
 * </p>
 */
package org.egothor.methodatlas.command;
