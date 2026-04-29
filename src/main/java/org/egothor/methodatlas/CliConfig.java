package org.egothor.methodatlas;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * @param fileSuffixes one or more filename suffixes used to select source
 *                     files for scanning; a file is included if its name
 *                     ends with any of the listed suffixes
 * @param testMarkers  language-neutral identifiers that mark test methods;
 *                     for JVM providers these are annotation simple names
 *                     (e.g. {@code "Test"}, {@code "ParameterizedTest"});
 *                     for .NET providers they are attribute names; TypeScript
 *                     providers typically ignore this and use function names
 *                     via {@code properties} instead; when empty, each
 *                     {@link org.egothor.methodatlas.api.TestDiscovery} provider
 *                     falls back to its own built-in defaults
 * @param properties   plugin-specific key/multi-value pairs forwarded verbatim
 *                     to each {@link org.egothor.methodatlas.api.TestDiscovery}
 *                     provider; providers ignore keys they do not recognise
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
 * @param securityOnly    when {@code true}, only methods classified as
 *                        security-relevant (via AI or override) are emitted;
 *                        methods without an AI suggestion or whose suggestion
 *                        has {@code securityRelevant=false} are silently
 *                        dropped from the output; this flag is set
 *                        automatically when {@link OutputMode#SARIF} is
 *                        selected unless {@code -include-non-security} is
 *                        supplied, because SARIF is consumed by security
 *                        tooling that expects findings, not a full inventory
 * @param aiCacheFile     path to a MethodAtlas CSV produced by a previous scan
 *                        with {@code -content-hash -ai}; when present, classes
 *                        whose {@code content_hash} matches an entry in that
 *                        file are classified from the cache instead of calling
 *                        the AI provider; {@code null} when no cache is configured
 * @param driftDetect          when {@code true}, a {@code tag_ai_drift} column is
 *                             added to CSV/plain output comparing the source-level
 *                             {@code @Tag("security")} annotation against the AI
 *                             security-relevance classification; values are
 *                             {@code none}, {@code tag-only}, or {@code ai-only};
 *                             SARIF and GitHub Annotations always include drift
 *                             when AI is enabled regardless of this flag
 * @param applyTagsFromCsvFile path to a CSV file used as input for the
 *                             {@code -apply-tags-from-csv} mode; {@code null}
 *                             when the mode is not active
 * @param mismatchLimit        maximum number of mismatches allowed before the
 *                             apply-tags-from-csv operation is aborted; {@code -1}
 *                             means no limit is enforced
 * @param emitSourceRoot       when {@code true}, a {@code source_root} column is
 *                             added to CSV output and a {@code SRCROOT=} token is
 *                             added to plain-text output, identifying which scan
 *                             root each record originated from; useful in
 *                             multi-root projects where the same fully qualified
 *                             class name can appear under different source trees
 *                             (e.g. module-a and module-b each contain
 *                             {@code com.acme.FooTest}); has no effect on SARIF
 *                             or GitHub Annotations output
 * @param sarifOmitScores      when {@code true}, the interaction score and
 *                             confidence percentage are omitted from SARIF result
 *                             message text; use this when the consuming system
 *                             already renders the {@code properties} bag and the
 *                             extra text in the message is unwanted; default is
 *                             {@code false} (scores are embedded in messages so
 *                             they are visible in GitHub Code Scanning and similar
 *                             tooling that does not render the properties bag)
 */
record CliConfig(OutputMode outputMode, AiOptions aiOptions, List<Path> paths, List<String> fileSuffixes,
        Set<String> testMarkers, Map<String, List<String>> properties, boolean emitMetadata,
        ManualMode manualMode, boolean applyTags, boolean contentHash, Path overrideFile,
        boolean securityOnly, Path aiCacheFile, boolean driftDetect, Path applyTagsFromCsvFile,
        int mismatchLimit, boolean emitSourceRoot, boolean sarifOmitScores) {
}
