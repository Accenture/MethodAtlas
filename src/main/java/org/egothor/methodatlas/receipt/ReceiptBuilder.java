// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.receipt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.egothor.methodatlas.CliConfig;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.PromptTemplateKind;
import org.egothor.methodatlas.ai.PromptTemplateSet;
import org.egothor.methodatlas.command.ContentHasher;

/**
 * Assembles a {@link ReproducibilityReceipt} from a parsed
 * {@link CliConfig} and the resolved tool version.
 *
 * <p>
 * Package-private because nothing outside the {@code receipt} package needs to
 * call this; {@code MethodAtlasApp} is the sole external caller, accessing it
 * through {@link #build(CliConfig, String, String)}.
 * </p>
 */
final class ReceiptBuilder {

    /**
     * Schema version of the JSON payload; bumped on any breaking change.
     *
     * <p>
     * v2 (4.1.0) replaced the single {@code inputs.promptTemplateHash} of v1 with
     * three per-template hashes ({@code classificationPromptHash},
     * {@code triageAppendixPromptHash}, {@code dedicatedTriagePromptHash}) and folds
     * all three into {@code configHash}. See {@code docs/usage-modes/reproducibility-receipts.md}.
     * </p>
     */
    private static final String SCHEMA_VERSION = "2";

    /** Algorithm string passed to {@link MessageDigest#getInstance(String)}. */
    private static final String SHA256_ALGO = "SHA-256";

    /** Sentinel for "no value" in the canonical key=value serialisation. */
    @SuppressWarnings("InlineTrivialConstant")
    private static final String EMPTY = "";

    // Canonical key=value separator characters: kept as constants so the
    // algorithm is unambiguous to an auditor re-deriving the hash from
    // standard tooling (e.g. sha256sum + printf).

    /** Separator between key and value in the canonical serialisation. */
    private static final String CONFIG_HASH_KV_SEP = "=";

    /** Separator between key=value pairs in the canonical serialisation. */
    private static final String CONFIG_HASH_SEPARATOR = "\n";

    // TreeMap keys: alphabetical insertion is enforced by TreeMap, not by the
    // declaration order here.

    /** TreeMap key for the AI cache file fingerprint. */
    private static final String KEY_AI_CACHE_FILE_SHA = "aiCacheFileSha256";
    /** TreeMap key for the configured AI model name. */
    private static final String KEY_AI_MODEL = "aiModel";
    /** TreeMap key for the configured AI provider. */
    private static final String KEY_AI_PROVIDER = "aiProvider";
    /** TreeMap key for the built-in taxonomy mode name. */
    private static final String KEY_BUILT_IN_TAXONOMY = "builtInTaxonomy";
    /** TreeMap key for the MethodAtlas tool version. */
    private static final String KEY_METHOD_ATLAS_VERSION = "methodAtlasVersion";
    /** TreeMap key for the override file fingerprint. */
    private static final String KEY_OVERRIDE_FILE_SHA = "overrideFileSha256";
    /** TreeMap key for the effective method-classification prompt template hash. */
    private static final String KEY_CLASSIFICATION_PROMPT = "classificationPromptHash";
    /** TreeMap key for the effective folded credential-triage appendix template hash. */
    private static final String KEY_TRIAGE_APPENDIX_PROMPT = "triageAppendixPromptHash";
    /** TreeMap key for the effective standalone credential-triage template hash. */
    private static final String KEY_DEDICATED_TRIAGE_PROMPT = "dedicatedTriagePromptHash";
    /** TreeMap key for the taxonomy file fingerprint. */
    private static final String KEY_TAXONOMY_FILE_SHA = "taxonomyFileSha256";

    /**
     * Rough capacity estimate for the canonical key=value buffer: ten keys
     * with combined length ≈ 200 characters plus up to ten 64-char SHA-256
     * values plus separators leaves the StringBuilder near its final size
     * without reallocations.
     */
    private static final int CANONICAL_BUFFER_CAPACITY = 1024;

    private ReceiptBuilder() {
        // Utility class.
    }

    /**
     * Builds a reproducibility receipt for the supplied configuration.
     *
     * <p>
     * The {@code configHash} field is computed as follows:
     * </p>
     * <ol>
     *   <li>A {@link TreeMap} is populated with the ten canonical keys
     *       documented at class scope. Absent inputs map to the empty
     *       string.</li>
     *   <li>The map is serialised as {@code key1=value1\n…keyN=valueN\n}
     *       using {@link StandardCharsets#UTF_8}; {@link TreeMap} guarantees
     *       alphabetical key order.</li>
     *   <li>SHA-256 is applied to those bytes and the result is emitted as
     *       lowercase hex via {@link HexFormat#of()}.</li>
     * </ol>
     *
     * @param config         parsed CLI configuration; must not be {@code null}
     * @param toolVersion    resolved tool version string (use {@code "dev"} when
     *                       no implementation version is available)
     * @param outputModeName textual name of the chosen output mode (e.g.
     *                       {@code "SARIF"}); included in the receipt's
     *                       {@code outputMode} field
     * @return a populated receipt with a stable {@code configHash} that an
     *         auditor can recompute from the other fields
     * @throws IOException if any input file referenced by {@code config}
     *                     (override file, taxonomy file, AI cache) cannot be
     *                     read for hashing
     */
    /* default */ static ReproducibilityReceipt build(CliConfig config, String toolVersion,
            String outputModeName) throws IOException {
        AiOptions ai = config.aiOptions();
        FileArtifact overrideArtifact = hashIfPresent(config.overrideFile());
        FileArtifact aiCacheArtifact = hashIfPresent(config.aiCacheFile());

        FileArtifact taxonomyArtifact = null;
        String builtInTaxonomy = null;
        if (ai.taxonomyFile() != null) {
            taxonomyArtifact = hashIfPresent(ai.taxonomyFile());
        } else {
            builtInTaxonomy = ai.taxonomyMode().name();
        }

        String aiProvider = ai.enabled() ? ai.provider().name() : null;
        String aiModel = ai.enabled() ? ai.modelName() : null;
        PromptTemplateSet templates = ai.promptTemplates();
        String classificationPromptHash = ai.enabled() ? templates.hash(PromptTemplateKind.CLASSIFICATION) : null;
        String triageAppendixPromptHash = ai.enabled() ? templates.hash(PromptTemplateKind.TRIAGE_APPENDIX) : null;
        String dedicatedTriagePromptHash = ai.enabled() ? templates.hash(PromptTemplateKind.DEDICATED_TRIAGE) : null;

        ReceiptInputs inputs = new ReceiptInputs(taxonomyArtifact, builtInTaxonomy,
                overrideArtifact, aiCacheArtifact, aiProvider, aiModel,
                classificationPromptHash, triageAppendixPromptHash, dedicatedTriagePromptHash);

        String configHash = computeConfigHash(toolVersion, ai, overrideArtifact, aiCacheArtifact,
                taxonomyArtifact, builtInTaxonomy,
                classificationPromptHash, triageAppendixPromptHash, dedicatedTriagePromptHash);

        return new ReproducibilityReceipt(
                SCHEMA_VERSION,
                Instant.now().toString(),
                toolVersion,
                javaVersion(),
                outputModeName,
                resolveScanRoots(config),
                computeDeterministicReplay(config),
                inputs,
                configHash);
    }

    /**
     * Returns a {@link FileArtifact} for {@code file} or {@code null} when
     * {@code file} is itself {@code null}.
     *
     * @param file path to fingerprint; may be {@code null}
     * @return populated artefact or {@code null}
     * @throws IOException if the file cannot be read
     */
    private static FileArtifact hashIfPresent(Path file) throws IOException {
        if (file == null) {
            return null;
        }
        String sha = ContentHasher.hashFile(file);
        return new FileArtifact(file.toAbsolutePath().toString(), sha);
    }

    /**
     * Resolves the JVM's reported Java version to a non-null string.
     *
     * @return Java version, or the literal {@code "unknown"} when the
     *         {@code java.version} property is unset
     */
    private static String javaVersion() {
        String v = System.getProperty("java.version");
        return v == null ? "unknown" : v;
    }

    /**
     * Maps scan roots to absolute path strings. An empty input list means
     * "scan the current directory"; the absolute resolution captures the
     * actual directory that was scanned.
     *
     * @param config parsed CLI configuration
     * @return absolute path strings for every scan root, in the order
     *         supplied on the command line
     */
    private static List<String> resolveScanRoots(CliConfig config) {
        List<Path> roots = config.paths();
        if (roots.isEmpty()) {
            return List.of(Path.of("").toAbsolutePath().toString());
        }
        return roots.stream().map(p -> p.toAbsolutePath().toString()).toList();
    }

    /**
     * Computes the {@code deterministicReplay} flag.
     *
     * @param config parsed CLI configuration
     * @return {@code true} when AI is disabled or an AI cache is configured
     */
    private static boolean computeDeterministicReplay(CliConfig config) {
        return !config.aiOptions().enabled() || config.aiCacheFile() != null;
    }

    /**
     * Produces the SHA-256 hex of the canonical {@code key=value} buffer.
     *
     * @param toolVersion        MethodAtlas tool version string
     * @param ai                 parsed AI options
     * @param overrideArtifact   override file artefact, or {@code null}
     * @param aiCacheArtifact    AI cache file artefact, or {@code null}
     * @param taxonomyArtifact   taxonomy file artefact, or {@code null}
     * @param builtInTaxonomy    built-in taxonomy mode name, or {@code null}
     * @param classificationPromptHash effective classification template hash, or {@code null}
     * @param triageAppendixPromptHash effective folded triage-appendix template hash, or {@code null}
     * @param dedicatedTriagePromptHash effective standalone triage template hash, or {@code null}
     * @return 64-character lowercase hex SHA-256
     */
    private static String computeConfigHash(String toolVersion, AiOptions ai,
            FileArtifact overrideArtifact, FileArtifact aiCacheArtifact,
            FileArtifact taxonomyArtifact, String builtInTaxonomy,
            String classificationPromptHash, String triageAppendixPromptHash,
            String dedicatedTriagePromptHash) {
        Map<String, String> keys = new TreeMap<>();
        keys.put(KEY_AI_CACHE_FILE_SHA, shaOrEmpty(aiCacheArtifact));
        keys.put(KEY_AI_MODEL, ai.enabled() && ai.modelName() != null ? ai.modelName() : EMPTY);
        keys.put(KEY_AI_PROVIDER, ai.enabled() ? ai.provider().name() : EMPTY);
        keys.put(KEY_BUILT_IN_TAXONOMY, builtInTaxonomy == null ? EMPTY : builtInTaxonomy);
        keys.put(KEY_CLASSIFICATION_PROMPT, classificationPromptHash == null ? EMPTY : classificationPromptHash);
        keys.put(KEY_DEDICATED_TRIAGE_PROMPT, dedicatedTriagePromptHash == null ? EMPTY : dedicatedTriagePromptHash);
        keys.put(KEY_METHOD_ATLAS_VERSION, toolVersion);
        keys.put(KEY_OVERRIDE_FILE_SHA, shaOrEmpty(overrideArtifact));
        keys.put(KEY_TAXONOMY_FILE_SHA, shaOrEmpty(taxonomyArtifact));
        keys.put(KEY_TRIAGE_APPENDIX_PROMPT, triageAppendixPromptHash == null ? EMPTY : triageAppendixPromptHash);

        StringBuilder canonical = new StringBuilder(CANONICAL_BUFFER_CAPACITY);
        keys.forEach((k, v) -> canonical.append(k).append(CONFIG_HASH_KV_SEP)
                .append(v).append(CONFIG_HASH_SEPARATOR));

        try {
            MessageDigest digest = MessageDigest.getInstance(SHA256_ALGO);
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Extracts the SHA-256 string from a {@link FileArtifact}, or returns the
     * empty-string sentinel when the artefact is absent.
     *
     * @param artifact artefact to inspect; may be {@code null}
     * @return SHA-256 hex or {@code ""}
     */
    private static String shaOrEmpty(FileArtifact artifact) {
        return artifact == null ? EMPTY : artifact.sha256();
    }
}
