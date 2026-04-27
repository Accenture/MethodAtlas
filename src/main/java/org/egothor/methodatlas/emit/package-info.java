/**
 * Output emitters for MethodAtlas scan results.
 *
 * <p>
 * This package contains formatting components that consume enriched test
 * method records and produce human- or machine-readable output. All emitters
 * implement or use {@link org.egothor.methodatlas.api.TestMethodSink} and
 * receive pre-computed {@link org.egothor.methodatlas.ai.AiMethodSuggestion}
 * data — they never call the AI engine directly.
 * </p>
 *
 * <h2>Emitters</h2>
 *
 * <ul>
 * <li>{@link org.egothor.methodatlas.emit.OutputEmitter} — emits CSV or
 *     plain-text records incrementally, one line per test method.</li>
 * <li>{@link org.egothor.methodatlas.emit.SarifEmitter} — buffers all records
 *     in memory and flushes a SARIF 2.1.0 JSON document at the end of the
 *     scan; suitable for GitHub Code Scanning upload.</li>
 * <li>{@link org.egothor.methodatlas.emit.GitHubAnnotationsEmitter} — emits
 *     {@code ::notice} and {@code ::warning} GitHub Actions workflow commands
 *     for inline PR annotations; does not require a GitHub Advanced Security
 *     licence.</li>
 * <li>{@link org.egothor.methodatlas.emit.DeltaEmitter} — formats and writes a
 *     MethodAtlas delta report comparing two scan CSV outputs.</li>
 * </ul>
 *
 * @see org.egothor.methodatlas.api.TestMethodSink
 * @see org.egothor.methodatlas.OutputMode
 */
package org.egothor.methodatlas.emit;
