// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.receipt;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serialises a {@link ReproducibilityReceipt} to disk as pretty-printed JSON.
 *
 * <p>
 * The writer takes a caller-supplied {@link ObjectMapper} so the same shared
 * instance can be reused across an entire JVM invocation (matches the
 * project-wide convention of not instantiating Jackson per call). The mapper
 * must already be configured for indented output and {@code NON_NULL}
 * inclusion (see {@link ReceiptFacade}); the writer does not mutate it, so it
 * is safe to share concurrently.
 * </p>
 *
 * <p>
 * Package-private because nothing outside the {@code receipt} package needs
 * direct access; {@code MethodAtlasApp} is the sole external caller.
 * </p>
 */
final class ReceiptWriter {

    private ReceiptWriter() {
        // Utility class.
    }

    /**
     * Serialises {@code receipt} to {@code outputFile} using {@code mapper}.
     *
     * <p>
     * The mapper must already be configured for indented, {@code NON_NULL}
     * output (as {@link ReceiptFacade} does). The writer does not modify the
     * mapper.
     * </p>
     *
     * @param receipt    receipt to serialise; must not be {@code null}
     * @param mapper     pre-configured Jackson mapper to use; must not be
     *                   {@code null}; the caller owns its lifecycle
     * @param outputFile destination path; the parent directory must already
     *                   exist
     * @throws IOException if the target file cannot be created or written
     */
    /* default */ static void write(ReproducibilityReceipt receipt, ObjectMapper mapper, Path outputFile)
            throws IOException {
        try (OutputStream out = Files.newOutputStream(outputFile)) {
            mapper.writeValue(out, receipt);
        }
    }
}
