// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gap;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialises a {@link GapReport} to disk as pretty-printed JSON.
 *
 * <p>
 * Package-private because nothing outside the {@code gap} package needs direct
 * access; {@link GapFacade} is the sole external caller.
 * </p>
 */
final class GapReportWriter {

    private GapReportWriter() {
        // Utility class.
    }

    /**
     * Writes {@code report} to {@code outputFile} using a freshly constructed
     * {@link ObjectMapper} configured with {@code INDENT_OUTPUT} and
     * {@code NON_NULL} inclusion.
     *
     * @param report     report to serialise
     * @param outputFile destination path; the parent directory must already exist
     * @throws IOException if the target file cannot be created or written
     */
    /* default */ static void write(GapReport report, Path outputFile) throws IOException {
        ObjectMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();
        try {
            mapper.writeValue(outputFile.toFile(), report);
        } catch (JacksonException e) {
            throw new IOException("Cannot write gap report to '" + outputFile + "'", e);
        }
    }
}
