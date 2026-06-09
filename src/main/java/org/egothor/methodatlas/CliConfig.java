package org.egothor.methodatlas;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.emit.OutputMode;

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
 *                             added to CSV/plain output identifying which scan root
 *                             each record originated from; useful in multi-root
 *                             projects; has no effect on SARIF or annotations output
 * @param sarifOmitScores      when {@code true}, the interaction score and
 *                             confidence percentage are omitted from SARIF result
 *                             message text; use this when the consuming system
 *                             already renders the {@code properties} bag; default
 *                             is {@code false} (scores are embedded in messages)
 * @param minConfidence        minimum AI confidence score (inclusive) for a method
 *                             to be emitted; only meaningful when
 *                             {@code -ai-confidence} is enabled; the default
 *                             {@code 0.0} disables the filter entirely
 * @param emitReceipt          when {@code true}, a reproducibility receipt
 *                             JSON file is written after a successful scan
 *                             capturing the SHA-256 of every input that
 *                             influenced the result
 * @param receiptFile          target path for the reproducibility receipt; when
 *                             {@code null}, the default
 *                             {@code methodatlas-receipt.json} in the current
 *                             working directory is used; only honoured when
 *                             {@code emitReceipt} is {@code true}
 * @param emitCoverage         when {@code true}, a control-coverage matrix is
 *                             produced after the scan from the mapping supplied
 *                             via {@code coverageMappingFile}
 * @param coverageFile         target path for the coverage matrix JSON; when
 *                             {@code null}, the default
 *                             {@code controls-coverage.json} in the current
 *                             working directory is used; only honoured when
 *                             {@code emitCoverage} is {@code true}
 * @param coverageMappingFile  user-authored tag→control mapping JSON file;
 *                             required when {@code emitCoverage} is {@code true}
 * @param evidencePackFramework target compliance framework token supplied to
 *                              {@code -evidence-pack}; {@code null} when the
 *                              evidence-pack mode is not active
 * @param evidencePackDir       output directory for the evidence pack; {@code null}
 *                              means use the default (under the first scan root)
 * @param evidencePackOverwrite when {@code true}, an existing evidence-pack
 *                              directory is reused; when {@code false}, a
 *                              pre-existing directory is treated as an error
 * @param evidencePackKeyringFile   ZeroEcho keyring file providing the manifest
 *                                  signing key (a plaintext {@code KeyringStore},
 *                                  not a JDK PKCS12 keystore); {@code null}
 *                                  produces an unsigned pack with a stderr warning;
 *                                  intended for CLI use with a permission-protected
 *                                  file
 * @param evidencePackKeyringEnv    name of an environment variable holding the
 *                                  full keyring content, or {@code null}; intended
 *                                  for CI/CD where the keyring is delivered through
 *                                  a platform secret and parsed in memory so the
 *                                  private key never touches the runner's disk;
 *                                  takes precedence over {@code evidencePackKeyringFile}
 * @param evidencePackKeyAlias  keyring alias of the signing key; {@code null}
 *                              uses the first alias; for hybrid signing the
 *                              format is {@code "classic/pqc"}
 * @param evidencePackSignAlgo  ZeroEcho signature algorithm identifier;
 *                              {@code null} derives the algorithm from the keyring
 *                              entry (Ed25519 when MethodAtlas-generated); a
 *                              {@code "classic+pqc"} value triggers hybrid mode
 * @param verbose               when {@code true}, emit detailed diagnostics to
 *                              the progress writer; consumed by
 *                              {@code -apply-tags-from-csv} to diagnose zero-update runs
 * @param promoteAi             <strong>risky, not recommended</strong>: when
 *                              {@code true}, {@code -apply-tags-from-csv} fills a
 *                              method's blank {@code tags} / {@code display_name}
 *                              from the AI columns, writing unvalidated AI output
 *                              into source; off by default (see the engine and
 *                              YAML docs for the full warning)
 * @param detectSecrets           when {@code true}, enable credential detection alongside the test scan
 * @param secretsInclude          glob overriding the default file mask; {@code null} uses the default
 * @param secretsRules            custom rule catalog YAML; {@code null} uses the built-in catalog
 * @param secretsOut              output path for the secrets CSV; default {@code methodatlas-credentials.csv}
 * @param secretsSeparateLlm      when {@code true}, force a standalone triage LLM call
 * @param secretsShowValues       when {@code true}, print unmasked secret values (default: redacted)
 * @param secretsErrorThreshold   SARIF {@code error} score floor (default {@code 0.8})
 * @param secretsWarningThreshold SARIF {@code warning} score floor (default {@code 0.4})
 * @param secretsMinScore         suppress findings below this score (default {@code 0.0} = keep all)
 * @param aiCacheOut              path to write the unified AI result cache (JSON Lines) after the
 *                                scan, or {@code null} to write none; pair with {@code aiCacheFile}
 *                                pointing at the same path for an incremental read-update cache
 * @since 3.0.0
 */
public record CliConfig(OutputMode outputMode, AiOptions aiOptions, List<Path> paths, List<String> fileSuffixes,
        Set<String> testMarkers, Map<String, List<String>> properties, boolean emitMetadata,
        ManualMode manualMode, boolean applyTags, boolean contentHash, Path overrideFile,
        boolean securityOnly, Path aiCacheFile, boolean driftDetect, Path applyTagsFromCsvFile,
        int mismatchLimit, boolean emitSourceRoot, boolean sarifOmitScores, double minConfidence,
        boolean emitReceipt, Path receiptFile,
        boolean emitCoverage, Path coverageFile, Path coverageMappingFile,
        String evidencePackFramework, Path evidencePackDir, boolean evidencePackOverwrite,
        Path evidencePackKeyringFile, String evidencePackKeyringEnv, String evidencePackKeyAlias,
        String evidencePackSignAlgo, boolean verbose, boolean promoteAi,
        boolean detectSecrets, String secretsInclude, Path secretsRules,
        Path secretsOut, boolean secretsSeparateLlm, boolean secretsShowValues,
        double secretsErrorThreshold, double secretsWarningThreshold, double secretsMinScore,
        Path aiCacheOut) {
}
