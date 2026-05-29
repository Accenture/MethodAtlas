// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.receipt;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Serialises a {@link ReproducibilityReceipt} to disk as pretty-printed JSON.
 *
 * <p>
 * The writer takes a caller-supplied {@link ObjectMapper} so the same shared
 * instance can be reused across an entire JVM invocation (matches the
 * project-wide convention of not instantiating Jackson per call). The supplied
 * mapper is reconfigured idempotently to enable indented output and to omit
 * absent optional fields via the {@code NON_NULL} inclusion policy.
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
     * The mapper is configured in-place with
     * {@link SerializationFeature#INDENT_OUTPUT} and
     * {@link JsonInclude.Include#NON_NULL}. Both settings are idempotent;
     * passing the same mapper repeatedly is safe.
     * </p>
     *
     * @param receipt    receipt to serialise; must not be {@code null}
     * @param mapper     Jackson mapper to use; must not be {@code null}; the
     *                   caller owns its lifecycle
     * @param outputFile destination path; the parent directory must already
     *                   exist
     * @throws IOException if the target file cannot be created or written
     */
    /* default */ static void write(ReproducibilityReceipt receipt, ObjectMapper mapper, Path outputFile)
            throws IOException {
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        try (OutputStream out = Files.newOutputStream(outputFile)) {
            mapper.writeValue(out, receipt);
        }
    }
}
