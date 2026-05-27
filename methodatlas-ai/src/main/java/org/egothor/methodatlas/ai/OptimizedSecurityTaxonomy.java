package org.egothor.methodatlas.ai;

/**
 * Provides the optimized built-in taxonomy used to guide AI-based security
 * classification when prompt compactness and model reliability are prioritized.
 *
 * <p>
 * This class supplies a condensed taxonomy definition intended for use with
 * {@link org.egothor.methodatlas.ai.AiOptions.TaxonomyMode#OPTIMIZED}. In
 * contrast to {@link DefaultSecurityTaxonomy}, this variant is structured to
 * improve AI classification consistency by reducing prompt verbosity while
 * preserving the same controlled category set and classification intent.
 * </p>
 *
 * <h2>Design Goals</h2>
 *
 * <ul>
 * <li>minimize prompt length without changing the supported taxonomy</li>
 * <li>increase deterministic model behavior</li>
 * <li>reduce ambiguity in category selection</li>
 * <li>preserve professional terminology and decision rules</li>
 * </ul>
 *
 * <p>
 * The taxonomy text returned by this class is intended to be embedded directly
 * into AI prompts and therefore favors concise, machine-oriented instruction
 * structure over explanatory prose.
 * </p>
 *
 * <p>
 * This class is a non-instantiable utility holder.
 * </p>
 *
 * @see DefaultSecurityTaxonomy
 * @see org.egothor.methodatlas.ai.AiSuggestionEngineImpl
 * @see org.egothor.methodatlas.ai.AiOptions.TaxonomyMode
 */
public final class OptimizedSecurityTaxonomy {
    /**
     * Prevents instantiation of this utility class.
     */
    private OptimizedSecurityTaxonomy() {
    }

    /**
     * Returns the optimized built-in taxonomy text used for AI classification.
     *
     * <p>
     * The returned taxonomy is a compact instruction set designed for large
     * language models performing security classification of JUnit test methods. It
     * preserves the same controlled tag set as the default taxonomy while
     * presenting the rules in a shorter, more model-oriented structure.
     * </p>
     *
     * <p>
     * The taxonomy defines:
     * </p>
     * <ul>
     * <li>the meaning of a security-relevant test</li>
     * <li>the mandatory {@code security} umbrella tag</li>
     * <li>the allowed category tags</li>
     * <li>selection rules for assigning taxonomy tags</li>
     * <li>guidance for use of the optional {@code owasp} tag</li>
     * <li>the required {@code SECURITY: <property> - <scenario>} display name
     * format</li>
     * </ul>
     *
     * <p>
     * This optimized variant is suitable when improved model consistency or shorter
     * prompt size is more important than human-oriented explanatory wording.
     * </p>
     *
     * @return optimized taxonomy text used to instruct AI classification
     *
     * @see DefaultSecurityTaxonomy#text()
     * @see org.egothor.methodatlas.ai.AiSuggestionEngineImpl
     */
    public static String text() {
        return """
                SECURITY TEST CLASSIFICATION SPECIFICATION
                ==========================================

                Goal
                ----

                Classify JUnit 5 test methods that validate security properties.

                The output MUST follow the allowed tag taxonomy and MUST NOT introduce new tags.


                Security-Relevant Test Definition
                ---------------------------------

                A test is security-relevant when it verifies any of the following:

                • authentication behavior
                • authorization decisions
                • cryptographic correctness
                • validation of untrusted input
                • protection against injection attacks
                • protection of sensitive data
                • security event logging
                • secure error handling

                If failure of the test could allow:

                • unauthorized access
                • data exposure
                • privilege escalation
                • security control bypass

                then the test is security-relevant.


                Mandatory Tag
                -------------

                Every security-relevant test MUST contain:

                security


                Allowed Category Tags
                ---------------------

                Only the following tags are permitted:

                auth
                access-control
                crypto
                input-validation
                injection
                data-protection
                logging
                error-handling
                owasp


                Category Semantics
                ------------------

                auth
                    authentication validation
                    identity verification
                    credential checks
                    token/session validation

                access-control
                    authorization enforcement
                    permission checks
                    role evaluation
                    ownership validation

                crypto
                    encryption/decryption
                    signature verification
                    key usage
                    nonce/IV rules
                    hashing or key derivation

                input-validation
                    validation of untrusted inputs
                    canonicalization
                    malformed input rejection
                    path normalization

                injection
                    protection against injection attacks
                    SQL/NoSQL injection
                    command injection
                    template injection
                    deserialization vulnerabilities

                data-protection
                    encryption of sensitive data
                    secret handling
                    PII protection
                    secure storage

                logging
                    security event logging
                    audit events
                    absence of secrets in logs

                error-handling
                    safe error messages
                    no information leakage
                    safe fallback behavior


                OWASP Tag
                ---------

                The `owasp` tag indicates that the test validates protection against a vulnerability
                category commonly described in OWASP guidance such as:

                • injection
                • broken authentication
                • broken access control
                • security misconfiguration
                • sensitive data exposure
                • insecure deserialization
                • cross-site scripting

                The `owasp` tag should only be used when the test clearly targets a known
                OWASP vulnerability category.

                Prefer combining `owasp` with a more precise taxonomy tag.


                Tag Selection Rules
                -------------------

                1. If a test validates a security property → include `security`.
                2. Add 1–3 additional category tags when applicable.
                3. Prefer the most specific tag.
                4. Do not assign tags when security relevance is unclear.
                5. Never invent new tags.


                Display Name Format
                -------------------

                SECURITY: <security property> - <scenario>

                Examples:

                SECURITY: access control - deny non-owner account access
                SECURITY: crypto - reject reused nonce in AEAD
                SECURITY: input validation - reject path traversal sequences
                """;
    }
}
