package org.egothor.methodatlas.emit;

import java.nio.file.Path;

import org.egothor.methodatlas.api.CredentialCandidate;

/**
 * A credential candidate enriched for output: the deterministic candidate, the
 * file it was found in, best-effort enclosing-method attribution, and optional
 * LLM triage results.
 *
 * <p>The {@code credibilityScore}, {@code endpoint}, and {@code rationale} fields
 * are populated only on AI-enabled runs; they are {@code null} on no-AI runs.
 * The {@code method} field is populated only when the containing file is a
 * discovered test class; otherwise it is {@code null}. The {@code fqcn} is the
 * best-effort fully qualified class name, also {@code null} for files that are
 * not discovered test classes.</p>
 *
 * @param candidate        the deterministic detection result; never {@code null}
 * @param filePath         path of the file the candidate was found in; never {@code null}
 * @param fqcn             best-effort fully qualified class name, or {@code null}
 *                         for a non-test file matched by a wider mask
 * @param method           enclosing test method simple name, or {@code null} when
 *                         the file is not a discovered test class
 * @param credibilityScore LLM credibility in {@code [0,1]}, or {@code null} on a
 *                         no-AI run
 * @param endpoint         LLM-attributed endpoint/system, or {@code null}
 * @param rationale        short LLM rationale, or {@code null}
 * @since 4.1.0
 */
public record CredentialFinding(
        CredentialCandidate candidate, Path filePath, String fqcn, String method,
        Double credibilityScore, String endpoint, String rationale) {
}
