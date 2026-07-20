// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.egothor.methodatlas.AiResultCache;
import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.egothor.methodatlas.command.ContentHasher;
import org.egothor.methodatlas.command.ScanOrchestrator;
import org.egothor.methodatlas.emit.ClassificationOverride;
import org.egothor.methodatlas.emit.CompositeTestMethodSink;
import org.egothor.methodatlas.emit.OutputEmitter;
import org.egothor.methodatlas.emit.OutputMode;
import org.egothor.methodatlas.emit.SarifEmitter;
import org.egothor.methodatlas.emit.TestMethodSink;

/**
 * Materialises a tamper-evident evidence pack on disk by running one scan and
 * bundling every artefact an auditor needs to verify it later.
 *
 * <p>
 * The command is selected from {@code MethodAtlasApp} when the user passes
 * {@code -evidence-pack <framework>}. It owns its output directory: it
 * creates the directory if absent, refuses to overwrite an existing one
 * unless {@code -evidence-pack-overwrite} was supplied, writes all
 * artefacts, computes a SHA-256 manifest, and optionally signs that manifest
 * via ZeroEcho.
 * </p>
 *
 * <p>
 * {@code MethodAtlasApp} is the only caller; the type is {@code public} so the
 * root package can construct it and read its {@link #outputDir()} and
 * {@link #framework()} for the post-run summary.
 * </p>
 *
 * @since 4.0.0
 */
public final class EvidencePackCommand {

    private static final Logger LOG = Logger.getLogger(EvidencePackCommand.class.getName());

    /** Exit code returned for success. */
    private static final int EXIT_OK = 0;

    /** Exit code returned when one or more files produced a scan error. */
    private static final int EXIT_SCAN_ERROR = 1;

    /** Subdirectory used when no explicit -evidence-pack-dir is supplied. */
    private static final String DEFAULT_PARENT = "evidence-packs";

    /** Manifest filename inside the pack. */
    private static final String MANIFEST_FILE = "manifest.sha256";

    /** Signed-envelope filename inside the pack. */
    private static final String MANIFEST_SIGNED_FILE = "manifest.sha256.signed";

    /** Filename of the SARIF artefact. */
    private static final String SARIF_FILE = "findings.sarif";

    /** Filename of the CSV artefact. */
    private static final String CSV_FILE = "findings.csv";

    /** Filename of the copied override file. */
    private static final String OVERRIDES_FILE = "overrides.yaml";

    /** Filename of the AI provenance file. */
    private static final String AI_RESPONSES_FILE = "ai-responses.jsonl";

    /** Filename of the pack metadata file. */
    private static final String META_FILE = "pack-meta.json";

    private final CliConfig cliConfig;
    private final EvidencePackOptions packOptions;
    private final TestDiscoveryConfig discoveryConfig;
    private final AiSuggestionEngine aiEngine;
    private final ClassificationOverride override;
    private final AiResultCache aiCache;
    private final ScanOrchestrator orchestrator;

    /**
     * Creates a new evidence-pack command.
     *
     * @param cliConfig       parsed CLI configuration
     * @param packOptions     evidence-pack–specific options
     * @param discoveryConfig discovery configuration forwarded to providers
     * @param aiEngine        AI engine, or {@code null} when AI is disabled
     * @param override        classification override
     * @param aiCache         AI result cache
     * @param orchestrator    pre-built scan orchestrator
     */
    public EvidencePackCommand(CliConfig cliConfig, EvidencePackOptions packOptions,
            TestDiscoveryConfig discoveryConfig, AiSuggestionEngine aiEngine,
            ClassificationOverride override, AiResultCache aiCache,
            ScanOrchestrator orchestrator) {
        this.cliConfig = cliConfig;
        this.packOptions = packOptions;
        this.discoveryConfig = discoveryConfig;
        this.aiEngine = aiEngine;
        this.override = override;
        this.aiCache = aiCache;
        this.orchestrator = orchestrator;
    }

    /**
     * Executes the command: runs the scan, writes every pack artefact, and —
     * when a keyring is configured — signs the manifest.
     *
     * <p>
     * Ordering matters for the integrity chain. Artefacts are written first,
     * then {@code pack-meta.json} (optimistically recording whether signing was
     * requested), then {@code manifest.sha256} (the SHA-256 of every artefact,
     * including {@code pack-meta.json}), and finally the manifest is signed. If
     * signing fails, {@code pack-meta.json} is rewritten as unsigned, any partial
     * {@code manifest.sha256.signed} is deleted, and {@code manifest.sha256} is
     * re-hashed so its {@code pack-meta.json} digest still matches the file on
     * disk. A signing failure is non-fatal — the pack is produced unsigned.
     * </p>
     *
     * @return {@code 0} on success, {@code 1} when one or more source files
     *         produced a parse or processing error
     * @throws IOException if any pack artefact cannot be written
     */
    public int execute() throws IOException {
        Path outputDir = resolveOutputDir();
        prepareOutputDir(outputDir);
        List<Path> roots = cliConfig.paths().isEmpty() ? List.of(Paths.get(".")) : cliConfig.paths();

        AiResponseArchive aiArchive = wireAiArchive();
        ScanResult scanResult = runScan(outputDir, roots);

        copyOverridesIfPresent(outputDir);
        aiArchive.flush(outputDir.resolve(AI_RESPONSES_FILE));

        SignResult signResult = signIfRequested(outputDir);
        writePackMeta(outputDir, roots, signResult);
        ManifestWriter.write(outputDir, outputDir.resolve(MANIFEST_FILE));

        if (signResult != null && signResult.signer != null) {
            // try-with-resources releases the signer's owned context (the hybrid
            // SignatureContext) once signing completes or fails.
            try (ZeroEchoSigner signer = signResult.signer) {
                signer.sign(
                        outputDir.resolve(MANIFEST_FILE),
                        outputDir.resolve(MANIFEST_SIGNED_FILE));
            } catch (IOException | GeneralSecurityException e) {
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING, "Manifest signing failed", e);
                }
                signResult.signFailed = true;
                // pack-meta.json was written with signed=true before the manifest
                // was hashed; now that signing failed it is rewritten as
                // signed=false. Discard any partial signature envelope and
                // re-hash the manifest so its pack-meta.json digest matches the
                // corrected file — otherwise an auditor would see a false tamper
                // mismatch on an (intentionally) unsigned pack.
                writePackMeta(outputDir, roots, signResult);
                Files.deleteIfExists(outputDir.resolve(MANIFEST_SIGNED_FILE));
                ManifestWriter.write(outputDir, outputDir.resolve(MANIFEST_FILE));
            }
        }

        return scanResult.hadErrors ? EXIT_SCAN_ERROR : EXIT_OK;
    }

    /**
     * Returns the absolute path of the produced pack directory. Useful for
     * the caller's success message.
     *
     * @return resolved absolute output directory
     */
    public Path outputDir() {
        return resolveOutputDir().toAbsolutePath();
    }

    /**
     * Returns the resolved framework name (canonical token).
     *
     * @return canonical framework token
     */
    public String framework() {
        return packOptions.framework().canonicalToken();
    }

    // -------------------------------------------------------------------------
    // Internal steps
    // -------------------------------------------------------------------------

    private Path resolveOutputDir() {
        if (packOptions.outputDir() != null) {
            return packOptions.outputDir();
        }
        Path base = cliConfig.paths().isEmpty() ? Paths.get(".") : cliConfig.paths().get(0);
        return base.resolve(DEFAULT_PARENT).resolve(packOptions.framework().canonicalToken());
    }

    private void prepareOutputDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            if (!packOptions.overwrite()) {
                throw new IOException("Evidence pack directory already exists (use "
                        + "-evidence-pack-overwrite to allow reuse): " + dir);
            }
            if (!Files.isDirectory(dir)) {
                throw new IOException("Evidence pack path is not a directory: " + dir);
            }
        } else {
            Files.createDirectories(dir);
        }
    }

    private AiResponseArchive wireAiArchive() {
        AiResponseArchive archive = new AiResponseArchive();
        if (aiEngine != null) {
            aiEngine.setResponseListener(archive);
        }
        return archive;
    }

    private ScanResult runScan(Path outputDir, List<Path> roots) throws IOException {
        boolean aiEnabled = aiEngine != null;
        boolean confidenceEnabled = aiEnabled && cliConfig.aiOptions().confidence();
        String filePrefix = ContentHasher.filePrefix(roots);

        SarifEmitter sarifEmitter = new SarifEmitter(aiEnabled, confidenceEnabled, filePrefix,
                !cliConfig.sarifOmitScores());

        try (PrintWriter csvWriter = new PrintWriter(
                new OutputStreamWriter(
                        Files.newOutputStream(outputDir.resolve(CSV_FILE)),
                        StandardCharsets.UTF_8), true)) {
            OutputEmitter csvEmitter = new OutputEmitter(csvWriter, aiEnabled, confidenceEnabled,
                    cliConfig.contentHash(), cliConfig.driftDetect(), false);
            csvEmitter.emitCsvHeader(OutputMode.CSV);

            TestMethodSink csvSink = (fqcn, method, beginLine, loc, contentHash, tags,
                    displayName, suggestion) ->
                csvEmitter.emit(OutputMode.CSV, fqcn, method, loc, contentHash, tags,
                        displayName, suggestion, null);

            TestMethodSink composite = new CompositeTestMethodSink(sarifEmitter, csvSink);
            TestMethodSink filtered = orchestrator.filterSink(composite, cliConfig.securityOnly(),
                    cliConfig.minConfidence(), confidenceEnabled);

            int result = orchestrator.scan(roots, cliConfig, discoveryConfig, aiEngine,
                    filtered, override, aiCache, null);

            // Surface any write error swallowed by the streaming CSV writer before
            // the manifest is hashed, so a truncated manifest.csv cannot be signed.
            csvEmitter.finish();

            writeSarif(outputDir, sarifEmitter);
            return new ScanResult(result != 0);
        }
    }

    private static void writeSarif(Path outputDir, SarifEmitter sarifEmitter) throws IOException {
        try (PrintWriter sarifWriter = new PrintWriter(
                new OutputStreamWriter(
                        Files.newOutputStream(outputDir.resolve(SARIF_FILE)),
                        StandardCharsets.UTF_8), true)) {
            sarifEmitter.flush(sarifWriter);
        }
    }

    private void copyOverridesIfPresent(Path outputDir) throws IOException {
        Path overrideFile = cliConfig.overrideFile();
        if (overrideFile != null && Files.exists(overrideFile)) {
            Files.copy(overrideFile, outputDir.resolve(OVERRIDES_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private SignResult signIfRequested(Path outputDir) {
        // A secret environment variable (CI/CD) takes precedence over a keyring
        // file (interactive CLI); when neither is configured the pack is unsigned.
        if (packOptions.keyringEnv() != null) {
            return signFromEnv(outputDir, packOptions.keyringEnv());
        }
        if (packOptions.keyringFile() != null) {
            return signFromFile(outputDir, packOptions.keyringFile());
        }
        return new SignResult(null, false);
    }

    private SignResult signFromEnv(Path outputDir, String envVar) {
        String keyringText = System.getenv(envVar);
        if (keyringText == null || keyringText.isBlank()) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Keyring environment variable {0} is unset or empty; pack will be unsigned",
                        envVar);
            }
            return new SignResult(null, true);
        }
        try {
            ZeroEchoSigner signer = ZeroEchoSigner.fromKeyringText(keyringText,
                    packOptions.keyAlias(), packOptions.signatureAlgorithm());
            return new SignResult(signer, false);
        } catch (IOException | GeneralSecurityException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Failed to initialise ZeroEcho signer from " + envVar + " for " + outputDir, e);
            }
            return new SignResult(null, true);
        }
    }

    private SignResult signFromFile(Path outputDir, Path keyringFile) {
        try {
            ZeroEchoSigner signer = ZeroEchoSigner.fromKeyringFile(keyringFile,
                    packOptions.keyAlias(), packOptions.signatureAlgorithm());
            return new SignResult(signer, false);
        } catch (IOException | GeneralSecurityException e) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Failed to initialise ZeroEcho signer for " + outputDir, e);
            }
            return new SignResult(null, true);
        }
    }

    private void writePackMeta(Path outputDir, List<Path> roots, SignResult signResult)
            throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("framework", packOptions.framework().canonicalToken());
        meta.put("methodAtlasVersion", versionString());
        meta.put("javaVersion", System.getProperty("java.version"));
        meta.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        meta.put("scanRoots", roots.stream().map(p -> p.toAbsolutePath().toString()).toList());
        meta.put("generatedUtc", Instant.now().toString());

        boolean signed = signResult != null && signResult.signer != null && !signResult.signFailed;
        meta.put("signed", signed);
        meta.put("signatureAlgorithm", signed ? signResult.signer.algorithm() : null);
        meta.put("zeroEchoLibVersion", signed ? ZeroEchoSigner.ZEROECHO_LIB_VERSION : null);
        meta.put("keyAlias", signed ? signResult.signer.resolvedAlias() : null);

        JsonMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        Files.writeString(outputDir.resolve(META_FILE),
                mapper.writeValueAsString(meta), StandardCharsets.UTF_8);
    }

    private static String versionString() {
        String impl = EvidencePackCommand.class.getPackage().getImplementationVersion();
        return impl != null ? impl : "dev";
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    /** Outcome of the scan step. */
    private static final class ScanResult {
        /** {@code true} when at least one provider reported errors. */
        private final boolean hadErrors;

        /* default */ ScanResult(boolean hadErrors) {
            this.hadErrors = hadErrors;
        }
    }

    /** Outcome of the optional signing step. */
    private static final class SignResult {
        /** Configured signer, or {@code null} when signing was skipped. */
        private final ZeroEchoSigner signer;

        /** Set to {@code true} when signing was attempted but failed. */
        private boolean signFailed;

        /* default */ SignResult(ZeroEchoSigner signer, boolean signFailed) {
            this.signer = signer;
            this.signFailed = signFailed;
        }
    }
}
