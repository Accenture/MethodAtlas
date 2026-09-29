// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.attest;

import java.util.List;

/**
 * A single security-relevant test method entry in an attestation manifest.
 *
 * <p>
 * Package-private because nothing outside the {@code attest} package constructs
 * entries directly; {@link AttestFacade} is the sole external entry point.
 * </p>
 *
 * @param fqcn        fully qualified class name
 * @param method      test method name
 * @param contentHash SHA-256 fingerprint of the enclosing class source, or
 *                    {@code null} when the scan was run without
 *                    {@code -content-hash}
 * @param aiTags      AI taxonomy tags assigned to this method; may be empty
 */
/* default */ record AttestEntry(String fqcn, String method, String contentHash,
        List<String> aiTags) {
}
