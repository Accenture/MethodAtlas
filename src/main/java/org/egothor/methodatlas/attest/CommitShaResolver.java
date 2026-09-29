// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.attest;

import java.util.List;

/**
 * Resolves the current commit SHA from well-known CI environment variables.
 *
 * <p>
 * Variables are tried in order; the first non-blank value wins. When none of
 * the known variables is set (e.g. on a local developer workstation) the
 * sentinel value {@code "unknown"} is returned.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code attest} package needs to
 * resolve the commit SHA independently; {@link AttestCollector} calls this
 * class at report-build time.
 * </p>
 */
final class CommitShaResolver {

    private static final List<String> ENV_VARS = List.of(
            "GITHUB_SHA",
            "CI_COMMIT_SHA",
            "GIT_COMMIT",
            "BUILD_SOURCEVERSION",
            "BITBUCKET_COMMIT");

    private CommitShaResolver() {
        // Utility class.
    }

    /**
     * Returns the commit SHA from the environment, or {@code "unknown"} when
     * no CI environment variable is set.
     *
     * @return non-null, non-empty commit SHA string or {@code "unknown"}
     */
    /* default */ static String resolve() {
        for (String var : ENV_VARS) {
            String val = System.getenv(var);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return "unknown";
    }
}
