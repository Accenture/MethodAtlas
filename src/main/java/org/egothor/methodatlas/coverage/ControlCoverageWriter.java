// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Serialises a {@link ControlCoverageReport} to disk as pretty-printed JSON.
 *
 * <p>
 * Package-private because nothing outside the {@code coverage} package needs
 * direct access; {@link CoverageFacade} is the sole external caller.
 * </p>
 */
final class ControlCoverageWriter {

    private ControlCoverageWriter() {
        // Utility class.
    }

    /**
     * Writes {@code report} to {@code outputFile} using a freshly constructed
     * {@link ObjectMapper} configured with {@code INDENT_OUTPUT} and
     * {@code NON_NULL} inclusion. A fresh mapper is intentional: the coverage
     * writer must not mutate or share configuration with mappers used by the
     * HTTP/AI subsystem.
     *
     * @param report     report to serialise
     * @param outputFile destination path; the parent directory must already
     *                   exist
     * @throws IOException if the target file cannot be created or written
     */
    /* default */ static void write(ControlCoverageReport report, Path outputFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.writeValue(outputFile.toFile(), report);
    }
}
