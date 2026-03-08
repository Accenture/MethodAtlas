package org.egothor.methodatlas.ai;

/**
 * Provides the default built-in taxonomy used to guide AI-based security
 * classification of JUnit test methods.
 *
 * <p>
 * This class exposes a human-readable taxonomy definition that is supplied to
 * the AI suggestion engine when no external taxonomy file is configured and
 * {@link org.egothor.methodatlas.ai.AiOptions.TaxonomyMode#DEFAULT} is
 * selected. The taxonomy defines the controlled vocabulary, decision rules, and
 * naming conventions used when classifying security-relevant tests.
 * </p>
 *
 * <h2>Purpose</h2>
 *
 * <p>
 * The taxonomy is designed to improve classification consistency by providing
 * the AI provider with a stable and explicit specification of:
 * </p>
 * <ul>
 * <li>what constitutes a security-relevant test</li>
 * <li>which security category tags are allowed</li>
 * <li>how tags should be selected</li>
 * <li>how security-oriented display names should be formed</li>
 * </ul>
 *
 * <p>
 * The default taxonomy favors readability and professional descriptive clarity.
 * For a more compact taxonomy tuned specifically for model reliability, see
 * {@link OptimizedSecurityTaxonomy}.
 * </p>
 *
 * <p>
 * This class is a non-instantiable utility holder.
 * </p>
 *
 * @see OptimizedSecurityTaxonomy
 * @see org.egothor.methodatlas.ai.AiSuggestionEngineImpl
 * @see org.egothor.methodatlas.ai.AiOptions.TaxonomyMode
 */
public final class DefaultSecurityTaxonomy {
    /**
     * Prevents instantiation of this utility class.
     */
    private DefaultSecurityTaxonomy() {
    }

    /**
     * Returns the default built-in taxonomy text used for AI classification.
     *
     * <p>
     * The returned text is intended to be embedded directly into provider prompts
     * and therefore contains both conceptual guidance and operational
     * classification rules. It defines:
     * </p>
     * <ul>
     * <li>scope of security-relevant tests</li>
     * <li>mandatory and optional tagging rules</li>
     * <li>allowed taxonomy categories</li>
     * <li>guidance for class-level versus method-level tagging</li>
     * <li>display name conventions</li>
     * <li>AI-oriented decision instructions</li>
     * </ul>
     *
     * <p>
     * The taxonomy includes the following category tags: {@code auth},
     * {@code access-control}, {@code crypto}, {@code input-validation},
     * {@code injection}, {@code data-protection}, {@code logging},
     * {@code error-handling}, and {@code owasp}.
     * </p>
     *
     * <p>
     * The returned value is immutable text and may safely be reused across multiple
     * AI requests.
     * </p>
     *
     * @return default taxonomy text used to instruct AI classification
     *
     * @see OptimizedSecurityTaxonomy#text()
     * @see org.egothor.methodatlas.ai.AiSuggestionEngineImpl
     */
    public static String text() {
        return """
                SECURITY TEST TAGGING TAXONOMY
                ==============================

                Purpose
                -------
                This taxonomy defines a controlled vocabulary for labeling security-relevant JUnit tests.
                The goal is to enable automated classification of test methods that validate security
                properties, controls, mitigations, or invariants.

                The taxonomy is intentionally small and stable to avoid uncontrolled tag proliferation.

                Classification Scope
                --------------------

                Applies to:
                - JUnit 5 test classes and methods
                - primarily unit tests
                - integration tests may follow the same model when applicable

                A test should be considered *security-relevant* if its failure could plausibly lead to:

                - loss of confidentiality
                - loss of integrity
                - loss of availability
                - unauthorized actions
                - exposure of sensitive data
                - bypass of security controls

                Examples of security-relevant verification:

                - access control decisions
                - authentication or identity validation
                - cryptographic correctness or misuse resistance
                - input validation or canonicalization
                - injection prevention
                - safe handling of sensitive data
                - correct security event logging
                - secure error handling


                Non-Security Tests (Out of Scope)
                ---------------------------------

                Do NOT classify tests as security tests when they only verify:

                - functional correctness unrelated to security
                - performance characteristics
                - UI behavior
                - formatting or presentation logic
                - internal implementation details with no security implications

                If a test contains security logic but its intent is purely functional,
                prefer NOT classifying it as a security test.


                Tagging Model
                -------------

                Every security-relevant test MUST include:

                    @Tag("security")

                and SHOULD include a descriptive display name:

                    @DisplayName("SECURITY: <control/property> - <scenario>")


                Example:

                    @Test
                    @Tag("security")
                    @Tag("access-control")
                    @DisplayName("SECURITY: access control - deny non-owner account access")

                Category tags provide additional classification.


                Allowed Category Tags
                ---------------------

                Only the following category tags may be used.

                Use lowercase and hyphenated names exactly as defined.


                1. auth
                -------

                Authentication and identity validation.

                Use when the test validates:

                - login or credential verification
                - authentication workflows
                - MFA enforcement
                - token validation
                - session binding
                - subject or identity claims

                Typical signals:

                - login handlers
                - token parsing
                - identity providers
                - credential verification


                2. access-control
                -----------------

                Authorization and permission enforcement.

                Use when the test validates:

                - role-based or attribute-based access control
                - ACL evaluation
                - policy decision logic
                - object ownership checks
                - deny-by-default behavior

                Typical signals:

                - permission checks
                - policy evaluation
                - role validation
                - ownership checks


                3. crypto
                ---------

                Cryptographic correctness or misuse resistance.

                Use when the test validates:

                - encryption and decryption
                - signature verification
                - key handling
                - nonce or IV requirements
                - secure randomness
                - hashing or key derivation

                Typical signals:

                - cryptographic libraries
                - key material
                - ciphersuites
                - signature APIs


                4. input-validation
                -------------------

                Validation or normalization of untrusted inputs.

                Use when the test validates:

                - schema validation
                - format validation
                - canonicalization rules
                - path normalization
                - rejection of malformed inputs

                Typical signals:

                - parsing logic
                - validation layers
                - normalization routines


                5. injection
                ------------

                Prevention of injection vulnerabilities.

                Use when the test validates protection against:

                - SQL injection
                - NoSQL injection
                - command injection
                - template injection
                - XPath/LDAP injection
                - deserialization attacks

                Typical signals:

                - query construction
                - escaping
                - parameterization
                - command execution


                6. data-protection
                ------------------

                Protection of sensitive or regulated data.

                Use when the test validates:

                - encryption of stored data
                - encryption in transit at unit level
                - masking or redaction
                - secret handling
                - secure storage of credentials

                Typical signals:

                - PII handling
                - encryption enforcement
                - secrets management


                7. logging
                ----------

                Security event logging and auditability.

                Use when the test validates:

                - absence of secrets in logs
                - presence of required audit events
                - correct security event messages
                - traceability identifiers

                Typical signals:

                - log assertions
                - audit event emission


                8. error-handling
                -----------------

                Security-safe error behavior.

                Use when the test validates:

                - absence of information leakage
                - sanitized error messages
                - safe fallback behavior
                - secure default behavior on failure

                Typical signals:

                - negative path tests
                - exception handling checks


                9. owasp
                --------

                Optional mapping tag linking the test to a widely recognized OWASP risk category.

                Use when the test explicitly addresses a vulnerability class defined by the
                OWASP Top 10 or related OWASP guidance.

                Examples include tests targeting:

                - injection vulnerabilities
                - broken authentication
                - broken access control
                - sensitive data exposure
                - security misconfiguration
                - insecure deserialization
                - cross-site scripting

                Important:

                The `owasp` tag should only be used when the test clearly maps to a well-known
                OWASP vulnerability category.

                When possible, the `owasp` tag should be combined with a more precise category
                from this taxonomy (for example `injection` or `access-control`).


                Tagging Rules
                -------------

                Mandatory rules:

                - Every security-relevant test MUST include the tag:

                      security

                - Security tests SHOULD include 1 to 3 category tags.

                - Category tags MUST be selected only from the allowed taxonomy.

                - Do NOT invent new tags.


                Class-Level vs Method-Level Tags
                --------------------------------

                Class-level tags may be used when:

                - all tests in the class validate the same security concern.

                Method-level tags should be used when:

                - only some tests are security-relevant
                - tests cover different security categories


                Display Name Convention
                -----------------------

                Security test names should follow this format:

                    SECURITY: <security property> - <test scenario>

                Examples:

                    SECURITY: access control - deny non-owner account access
                    SECURITY: crypto - reject reused nonce in AEAD
                    SECURITY: input validation - reject path traversal sequences


                AI Classification Guidance
                --------------------------

                When classifying tests:

                1. Identify the security property validated.
                2. Determine whether the test enforces or validates a security control.
                3. Assign the umbrella tag `security` when applicable.
                4. Select 1–3 category tags that best describe the security concern.
                5. Prefer specific categories over broad ones.
                6. Avoid assigning tags when the security intent is unclear.
                """;
    }
}