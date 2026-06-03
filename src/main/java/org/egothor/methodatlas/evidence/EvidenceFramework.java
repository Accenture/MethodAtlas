// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compliance frameworks that the {@code -evidence-pack} CLI mode can target.
 *
 * <p>
 * Each enum constant carries the canonical token used in pack metadata and in
 * the default output directory name. Tokens are kept stable because external
 * audit tooling treats them as identifiers, not labels.
 * </p>
 *
 * <p>
 * The {@link #parse(String)} factory accepts case-insensitive input but always
 * emits the canonical form in metadata files.
 * </p>
 *
 * @see EvidencePackOptions
 */
public enum EvidenceFramework {

    /** OWASP Application Security Verification Standard. */
    ASVS("ASVS"),

    /** PCI DSS requirement 6.4.1 (software security requirements). */
    PCI_6_4_1("PCI-6.4.1"),

    /** NIST Secure Software Development Framework, practice PW.8. */
    NIST_SSDF_PW8("NIST-SSDF-PW.8"),

    /** ISO/IEC 27001:2022 control 8.29 (secure development lifecycle). */
    ISO_27001_8_29("ISO-27001-8.29");

    private final String canonicalToken;

    EvidenceFramework(String canonicalToken) {
        this.canonicalToken = canonicalToken;
    }

    /**
     * Returns the canonical, case-sensitive token used to identify this
     * framework in pack metadata and on disk.
     *
     * @return canonical token; never {@code null}
     */
    public String canonicalToken() {
        return canonicalToken;
    }

    /**
     * Parses a framework token supplied on the command line.
     *
     * <p>
     * Matching is case-insensitive. The accepted tokens are exactly the
     * {@link #canonicalToken()} values of the enum constants.
     * </p>
     *
     * @param token raw token supplied by the user; must not be {@code null}
     * @return the matching enum constant; never {@code null}
     * @throws IllegalArgumentException if {@code token} does not match any
     *                                  known framework; the exception message
     *                                  lists every valid token
     */
    public static EvidenceFramework parse(String token) {
        String upper = token.toUpperCase(Locale.ROOT);
        for (EvidenceFramework framework : values()) {
            if (framework.canonicalToken.toUpperCase(Locale.ROOT).equals(upper)) {
                return framework;
            }
        }
        String valid = Stream.of(values())
                .map(EvidenceFramework::canonicalToken)
                .collect(Collectors.joining(", "));
        throw new IllegalArgumentException(
                "Unknown framework '" + token + "'. Valid values: " + valid);
    }
}
