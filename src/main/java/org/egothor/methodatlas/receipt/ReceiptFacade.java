// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.receipt;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.egothor.methodatlas.CliConfig;

/**
 * Single public entry point used by {@code MethodAtlasApp} to materialise a
 * reproducibility receipt.
 *
 * <p>
 * The facade exists because {@link ReceiptBuilder} and {@link ReceiptWriter}
 * are intentionally package-private — that keeps their helper methods,
 * canonical-form constants, and Jackson configuration off the public API
 * surface. The CLI orchestrator lives in a different package and would
 * otherwise need each of those classes promoted to {@code public}, which
 * would invite incidental external coupling.
 * </p>
 *
 * <p>
 * The shared {@link ObjectMapper} is cached on the facade so the CLI never
 * pays Jackson's first-call cost more than once per JVM invocation; reuse
 * across calls is safe because the mapper is configured idempotently.
 * </p>
 */
public final class ReceiptFacade {

    /** Default filename when {@code -receipt-file} is not supplied. */
    private static final String DEFAULT_RECEIPT_FILENAME = "methodatlas-receipt.json";

    private ReceiptFacade() {
        // Utility class.
    }

    /**
     * Initialisation-on-demand holder for the shared mapper. JVM class
     * initialisation semantics give thread-safe, race-free lazy creation
     * without {@code volatile} or {@code synchronized}.
     */
    private static final class MapperHolder {
        /**
         * Shared, fully configured mapper instance; safe to reuse across calls.
         * It is built once with indented output and {@code NON_NULL} inclusion so
         * that callers never reconfigure it (which would not be thread-safe).
         */
        /* default */ static final ObjectMapper INSTANCE = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();
    }

    /**
     * Builds and writes a reproducibility receipt for the supplied scan
     * configuration.
     *
     * <p>
     * Resolves the output path from {@link CliConfig#receiptFile()} when set;
     * otherwise falls back to {@code methodatlas-receipt.json} in the current
     * working directory. The Jackson mapper is shared across calls; see the
     * class-level Javadoc for the rationale.
     * </p>
     *
     * @param config         parsed CLI configuration; must not be {@code null}
     * @param toolVersion    resolved tool version string ({@code "dev"} when the
     *                       JAR manifest has no implementation version)
     * @param outputModeName textual name of the chosen output mode (e.g.
     *                       {@code "SARIF"}); persisted in the receipt
     * @throws IOException if any input file cannot be read for hashing or the
     *                     receipt file cannot be written
     */
    public static void emit(CliConfig config, String toolVersion, String outputModeName)
            throws IOException {
        ReproducibilityReceipt receipt = ReceiptBuilder.build(config, toolVersion, outputModeName);
        Path target = config.receiptFile() != null
                ? config.receiptFile()
                : Path.of(DEFAULT_RECEIPT_FILENAME);
        ReceiptWriter.write(receipt, mapper(), target);
    }

    /**
     * Returns the shared Jackson mapper, instantiated on first reference to
     * the inner {@link MapperHolder} class.
     *
     * @return shared {@link ObjectMapper}; never {@code null}
     */
    private static ObjectMapper mapper() {
        return MapperHolder.INSTANCE;
    }
}
