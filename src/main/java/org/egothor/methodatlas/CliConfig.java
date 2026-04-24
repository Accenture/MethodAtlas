package org.egothor.methodatlas;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.egothor.methodatlas.ai.AiOptions;

/**
 * Parsed command-line configuration used to drive a single application run.
 *
 * <p>
 * Instances are created by {@link CliArgs#parse(String...)} and consumed by
 * {@link MethodAtlasApp#run(String[], java.io.PrintWriter)}.
 * </p>
 *
 * @param outputMode      selected output mode
 * @param aiOptions       AI configuration controlling provider selection, taxonomy,
 *                        limits, and timeouts
 * @param paths           root paths to scan; when empty, the current working
 *                        directory is scanned
 * @param fileSuffixes    one or more filename suffixes used to select source
 *                        files for scanning; a file is included if its name
 *                        ends with any of the listed suffixes
 * @param testAnnotations set of annotation simple names used to identify test
 *                        methods; defaults to
 *                        {@link AnnotationInspector#DEFAULT_TEST_ANNOTATIONS}
 * @param emitMetadata    whether to emit {@code # key: value} metadata comment
 *                        lines before the CSV header
 * @param manualMode      manual AI workflow mode, or {@code null} when using
 *                        automated providers
 * @param applyTags       when {@code true}, AI-generated {@code @DisplayName}
 *                        and {@code @Tag} annotations are written back to the
 *                        source files instead of producing a CSV/SARIF report
 * @param contentHash     when {@code true}, a SHA-256 fingerprint of each
 *                        class source is included as a {@code content_hash}
 *                        column in CSV/plain output and as a SARIF property
 * @param overrideFile    path to a YAML classification override file, or
 *                        {@code null} when no override file is configured;
 *                        overrides are applied after AI classification (or in
 *                        place of it in static mode) and persist human
 *                        corrections across re-runs
 */
record CliConfig(OutputMode outputMode, AiOptions aiOptions, List<Path> paths, List<String> fileSuffixes,
        Set<String> testAnnotations, boolean emitMetadata, ManualMode manualMode, boolean applyTags,
        boolean contentHash, Path overrideFile) {
}
