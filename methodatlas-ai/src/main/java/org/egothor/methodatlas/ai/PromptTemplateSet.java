package org.egothor.methodatlas.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The complete set of prompt templates MethodAtlas uses for AI classification and
 * credential triage, each a plain-text body with {@code {token}} placeholders.
 *
 * <p>
 * A {@code PromptTemplateSet} is the single source of truth for the prompt wording
 * of one run. The built-in set ({@link #defaults()}) reproduces the tool's standard
 * prompts; a user may override any member with their own file. The same resolved set
 * is used both by {@link PromptBuilder} to render the prompts sent to the provider
 * and by the reproducibility receipt to record each template's SHA-256, so the
 * checksum an auditor sees always matches the text that was actually sent.
 * </p>
 *
 * <p>
 * Hashing is performed over the template body <em>with placeholders unfilled</em>:
 * per-run data (class source, taxonomy, candidate list) never influences the hash —
 * only the prompt-engineering structure does.
 * </p>
 *
 * <p>
 * This type is an immutable, thread-safe value object.
 * </p>
 *
 * @param classification  the method-classification template; never {@code null}
 * @param triageAppendix  the credential-triage appendix appended to a classification
 *                        prompt in folded mode; never {@code null}
 * @param dedicatedTriage the standalone credential-triage template; never {@code null}
 * @since 4.1.0
 */
public record PromptTemplateSet(String classification, String triageAppendix, String dedicatedTriage) {

    /** SHA-256 algorithm identifier; mandated by the Java SE specification. */
    private static final String SHA256_ALGO = "SHA-256";

    /**
     * Built-in method-classification template. The {@code {confidenceRules}} and
     * {@code {confidenceField}} tokens render to empty strings unless per-method
     * confidence scoring is requested.
     */
    /* default */ static final String DEFAULT_CLASSIFICATION = """
            You are analyzing a single JUnit 5 test class and suggesting security tags.

            TASK
            - Analyze the WHOLE class for context.
            - Classify ONLY the methods explicitly listed in TARGET TEST METHODS.
            - Do not invent methods that do not exist.
            - Do not classify helper methods, lifecycle methods, nested classes, or any method not listed.
            - Be conservative.
            - If uncertain, classify the method as securityRelevant=false.
            - Ignore pure functional / performance / UX tests unless they explicitly validate a security property.

            CONTROLLED TAXONOMY
            {taxonomy}

            TARGET TEST METHODS
            The following methods were extracted deterministically by the parser and are the ONLY methods
            you are allowed to classify. Use the full class source only as context for understanding them.

            {methods}

            OUTPUT RULES
            - Return JSON only.
            - No markdown.
            - No prose outside JSON.
            - Return exactly one result for each target method.
            - methodName values in the output must exactly match one of:
              [{expectedMethodNames}]
            - Do not omit any listed method.
            - Do not include any additional methods.
            - Tags must come only from this closed set:
              security, auth, access-control, crypto, input-validation, injection, data-protection, logging, error-handling, owasp
            - If securityRelevant=true, tags MUST include "security".
            - Add 1-3 tags total per method.
            - If securityRelevant=false, displayName must be null.
            - If securityRelevant=false, tags must be [].
            - If securityRelevant=true, displayName must match:
              SECURITY: <control/property> - <scenario>
            - reason should be short and specific.
            - interactionScore must be a decimal between 0.0 and 1.0 (one decimal place is sufficient).
              It measures what fraction of this test's assertions only verify *interactions* (that
              methods were called, in what order, with what arguments) rather than *outcomes* (return
              values, computed state, thrown exceptions, or observable side effects).
              Use these anchor points:
                1.0 — EVERY assertion is an interaction check (e.g. verify() only); NO assertion
                      verifies any return value, output field, database row, or observable outcome.
                0.0 — ALL assertions verify actual outputs or state; no interaction-only checks.
                0.5 — mixed: some real-output assertions alongside interaction checks.
              Score 1.0 only when there is NO assertion on any return value, state change, or
              observable outcome. A test that has even one meaningful output assertion scores ≤ 0.5.
              This applies regardless of testing framework (Mockito, EasyMock, WireMock, etc.).{confidenceRules}

            JSON SHAPE
            {
              "className": "string",
              "classSecurityRelevant": true,
              "classTags": ["security", "crypto"],
              "classReason": "string",
              "methods": [
                {
                  "methodName": "string",
                  "securityRelevant": true,
                  "displayName": "SECURITY: ...",
                  "tags": ["security", "crypto"],{confidenceField}
                  "reason": "string",
                  "interactionScore": 0.0
                }
              ]
            }

            CLASS
            FQCN: {fqcn}

            SOURCE
            {classSource}
            """;

    /**
     * Built-in credential-triage appendix. Appended to a rendered classification
     * prompt so one provider call returns both the {@code methods} and {@code secrets}
     * arrays; the class source is therefore transmitted only once.
     */
    /* default */ static final String DEFAULT_TRIAGE_APPENDIX = """

            ADDITIONAL TASK — credential triage.
            The following credential candidates were detected deterministically in this class.
            In ADDITION to the methods array, include a top-level "secrets" JSON array with one
            entry per candidateIndex below. For each, judge whether it is a GENUINE, live
            credential (not a placeholder/example) and identify the endpoint or system it
            authenticates against — the URL or service may appear elsewhere in the class, or the
            credential may be passed into a login/connect method. Score ONLY the listed
            candidates; do not invent any.
            Each entry: {"candidateIndex": <int>, "credibilityScore": <0.0-1.0>, "endpoint": <string-or-null>, "rationale": <string>}.

            CREDENTIAL CANDIDATES
            {candidates}
            """;

    /** Built-in standalone credential-triage template. */
    /* default */ static final String DEFAULT_DEDICATED_TRIAGE = """
            You are triaging credential candidates found in a test class by a deterministic scanner.

            TASK
            - For EACH listed candidate, decide whether it is a GENUINE, live credential or a
              placeholder / example / false positive.
            - Identify the endpoint or system the credential authenticates against. The URL or
              service may appear elsewhere in the class, or the credential may be passed into a
              login / connect method rather than embedded in a URL.
            - Score ONLY the candidates listed below, by their candidateIndex. Do not invent candidates.

            CANDIDATES
            {candidates}

            OUTPUT RULES
            - Return JSON only. No markdown. No prose outside JSON.
            - Return exactly one entry per listed candidateIndex.
            - credibilityScore is a decimal in [0.0, 1.0]: 1.0 = almost certainly a real, live
              credential; 0.0 = almost certainly a placeholder, example, or false positive.
            - endpoint is the system or URL the credential authenticates against, or null if unknown.
            - rationale is short and specific.

            JSON SHAPE
            {
              "secrets": [
                { "candidateIndex": 0, "credibilityScore": 0.0, "endpoint": "string-or-null", "rationale": "string" }
              ]
            }

            CLASS
            FQCN: {fqcn}

            SOURCE
            {classSource}
            """;

    private static final PromptTemplateSet DEFAULTS =
            new PromptTemplateSet(DEFAULT_CLASSIFICATION, DEFAULT_TRIAGE_APPENDIX, DEFAULT_DEDICATED_TRIAGE);

    /**
     * Validates that no member is {@code null}.
     *
     * @throws NullPointerException if any template is {@code null}
     */
    public PromptTemplateSet {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(triageAppendix, "triageAppendix");
        Objects.requireNonNull(dedicatedTriage, "dedicatedTriage");
    }

    /**
     * Returns the built-in template set reproducing MethodAtlas's standard prompts.
     *
     * @return the shared immutable default set; never {@code null}
     */
    public static PromptTemplateSet defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the template body for the given kind.
     *
     * @param kind the template kind; must not be {@code null}
     * @return the template text; never {@code null}
     * @throws NullPointerException if {@code kind} is {@code null}
     */
    public String get(PromptTemplateKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case CLASSIFICATION -> classification;
            case TRIAGE_APPENDIX -> triageAppendix;
            case DEDICATED_TRIAGE -> dedicatedTriage;
        };
    }

    /**
     * Returns a copy of this set with the template for {@code kind} replaced.
     *
     * @param kind the kind to replace; must not be {@code null}
     * @param body the replacement template body; must not be {@code null}
     * @return a new set differing only in the named member; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public PromptTemplateSet with(PromptTemplateKind kind, String body) {
        Objects.requireNonNull(body, "body");
        return switch (Objects.requireNonNull(kind, "kind")) {
            case CLASSIFICATION -> new PromptTemplateSet(body, triageAppendix, dedicatedTriage);
            case TRIAGE_APPENDIX -> new PromptTemplateSet(classification, body, dedicatedTriage);
            case DEDICATED_TRIAGE -> new PromptTemplateSet(classification, triageAppendix, body);
        };
    }

    /**
     * Returns the SHA-256 hex digest of the template body for the given kind.
     *
     * <p>
     * The digest is over the raw template text including its {@code {token}}
     * placeholders, so it identifies the prompt-engineering structure independently
     * of any per-run data. It is recorded in the reproducibility receipt.
     * </p>
     *
     * @param kind the template kind; must not be {@code null}
     * @return a 64-character lowercase hexadecimal SHA-256 digest; never {@code null}
     * @throws NullPointerException if {@code kind} is {@code null}
     * @see <a href="https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.180-4.pdf">FIPS 180-4</a>
     */
    public String hash(PromptTemplateKind kind) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA256_ALGO);
            return HexFormat.of().formatHex(digest.digest(get(kind).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
