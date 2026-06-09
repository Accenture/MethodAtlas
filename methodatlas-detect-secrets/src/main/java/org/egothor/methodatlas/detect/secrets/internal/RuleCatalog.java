package org.egothor.methodatlas.detect.secrets.internal;

import java.util.List;

/**
 * An immutable set of {@link CredentialRule}s plus the SHA-256 of the source text
 * they were parsed from (used as a reproducibility-receipt input).
 *
 * @param rules  the parsed rules; never {@code null}
 * @param sha256 lowercase hex SHA-256 of the catalog source; never {@code null}
 * @since 4.1.0
 */
public record RuleCatalog(List<CredentialRule> rules, String sha256) {

    /**
     * Defensively copies {@code rules}.
     */
    public RuleCatalog {
        rules = List.copyOf(rules);
    }
}
