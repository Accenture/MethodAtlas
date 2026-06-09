package org.egothor.methodatlas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads a YAML configuration file that provides default values for
 * command-line options.
 *
 * <p>
 * When a {@code -config <file>} argument is present, {@link CliArgs} calls
 * {@link #load(Path)} before processing the remaining arguments. The returned
 * {@link YamlConfigFile} seeds the initial values; any matching command-line
 * flag then overrides the YAML value.
 * </p>
 *
 * <h2>Supported fields</h2>
 *
 * <pre>
 * outputMode: csv          # csv | plain | sarif | json  (default: csv)
 * emitMetadata: false      # (default: false)
 * contentHash: false       # (default: false)
 * securityOnly: false      # (default: false)
 * includeNonSecurity: false  # opt-in: include non-security methods in SARIF output (default: false)
 * sarifOmitScores: false   # opt-out: omit interaction score / confidence from SARIF message text (default: false)
 * minConfidence: 0.0       # drop AI results below this threshold (requires ai.confidence: true; default: 0.0 = off)
 * driftDetect: false       # (default: false)
 * promoteAi: false         # RISKY, not recommended: -apply-tags-from-csv falls back to ai_tags/ai_display_name
 *                          # for methods whose curated tags/display_name are blank, writing UNVALIDATED AI
 *                          # output into source (default: false)
 * overrideFile: .methodatlas-overrides.yaml  # optional
 * fileSuffixes:
 *   - Test.java
 * testMarkers:             # annotation/attribute names; empty = provider defaults
 *   - Test
 *   - ParameterizedTest
 * properties:              # plugin-specific key/multi-value pairs (optional)
 *   functionNames:         # example: for a Jest/Mocha/Vitest TypeScript plugin
 *     - test
 *     - it
 * ai:
 *   enabled: true
 *   provider: ollama       # auto | ollama | openai | openrouter | anthropic | azure_openai | groq | xai | github_models | mistral
 *   model: qwen2.5-coder:7b
 *   baseUrl: http://localhost:11434
 *   apiKey: sk-...
 *   apiKeyEnv: MY_KEY_ENV
 *   taxonomyFile: /path/to/taxonomy.txt
 *   taxonomyMode: default  # default | optimized
 *   maxClassChars: 100000
 *   timeoutSec: 30
 *   maxRetries: 3
 *   confidence: false
 *   apiVersion: 2024-02-01 # Azure OpenAI REST API version (azure_openai only)
 * detectSecrets: false         # enable credential detection (default: false)
 * secretsInclude: "**&#47;*.java" # glob override for file mask (default: null = use fileSuffixes)
 * secretsRules: /path/to/rules.yaml  # custom rule catalog (default: null = built-in)
 * secretsOut: methodatlas-credentials.csv  # output path for secrets CSV (default: methodatlas-credentials.csv)
 * secretsSeparateLlm: false    # force standalone triage LLM call (default: false)
 * secretsShowValues: false     # print unmasked values (default: false)
 * secretsErrorThreshold: 0.8  # SARIF error score floor (default: 0.8)
 * secretsWarningThreshold: 0.4 # SARIF warning score floor (default: 0.4)
 * secretsMinScore: 0.0         # suppress findings below this score (default: 0.0 = keep all)
 * </pre>
 *
 * <p>
 * Unknown fields in the YAML file are silently ignored.
 * </p>
 *
 * @see CliArgs
 */
final class YamlConfig {

    /**
     * Prevents instantiation of this utility class.
     */
    private YamlConfig() {
    }

    /**
     * Loads a YAML configuration file.
     *
     * @param configFile path to the YAML file
     * @return parsed configuration; never {@code null}
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file cannot be parsed as valid YAML
     */
    /* default */ static YamlConfigFile load(Path configFile) throws IOException {
        ObjectMapper mapper = YAMLMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
        try {
            return mapper.readValue(configFile.toFile(), YamlConfigFile.class);
        } catch (JacksonException e) {
            throw new IOException("Cannot read or parse configuration file '" + configFile + "'", e);
        }
    }

    // -------------------------------------------------------------------------
    // POJO classes
    // -------------------------------------------------------------------------

    /**
     * Top-level YAML configuration structure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    /* default */ static final class YamlConfigFile {

        /** Output mode: {@code csv}, {@code plain}, or {@code sarif}. */
        @JsonProperty("outputMode")
        /* default */ String outputMode;

        /** Whether to emit {@code # key: value} metadata comment lines. */
        @JsonProperty("emitMetadata")
        /* default */ boolean emitMetadata;

        /** File name suffixes used to select test source files. */
        @JsonProperty("fileSuffixes")
        /* default */ List<String> fileSuffixes;

        /**
         * Language-neutral test-marker identifiers (annotation/attribute simple
         * names for JVM and .NET providers; ignored by TypeScript providers).
         * Empty or absent means "use provider defaults".
         */
        @JsonProperty("testMarkers")
        /* default */ List<String> testMarkers;

        /**
         * Plugin-specific key/multi-value pairs forwarded verbatim to each
         * {@link org.egothor.methodatlas.api.TestDiscovery} provider.
         * Providers ignore keys they do not recognise.
         */
        @JsonProperty("properties")
        /* default */ Map<String, List<String>> properties;

        /**
         * Whether to include a SHA-256 content-hash fingerprint of each class
         * source as a {@code content_hash} column.
         */
        @JsonProperty("contentHash")
        /* default */ boolean contentHash;

        /**
         * Path to a YAML classification override file. When set, human-authored
         * corrections are applied after AI classification on every run.
         */
        @JsonProperty("overrideFile")
        /* default */ String overrideFile;

        /**
         * When {@code true}, only security-relevant methods are emitted; all
         * other methods are silently dropped from the output.
         */
        @JsonProperty("securityOnly")
        /* default */ boolean securityOnly;

        /**
         * When {@code true}, non-security methods are included in SARIF output
         * even though SARIF mode applies the security-only filter by default.
         * Has no effect in CSV or plain-text modes.
         */
        @JsonProperty("includeNonSecurity")
        /* default */ boolean includeNonSecurity;

        /**
         * When {@code true}, a {@code tag_ai_drift} column is added to CSV/plain
         * output comparing the source-level {@code @Tag("security")} annotation
         * against the AI security-relevance classification.
         */
        @JsonProperty("driftDetect")
        /* default */ boolean driftDetect;

        /**
         * <strong>Risky, not recommended.</strong> When {@code true}, the
         * {@code -apply-tags-from-csv} engine falls back to the {@code ai_tags}
         * and {@code ai_display_name} columns for any method whose curated
         * {@code tags} / {@code display_name} column is blank, writing the raw,
         * unvalidated AI suggestion into source. This bypasses the human review
         * step the apply-from-csv workflow exists to enforce. Default:
         * {@code false}.
         */
        @JsonProperty("promoteAi")
        /* default */ boolean promoteAi;

        /**
         * When {@code true}, the interaction score and confidence percentage are
         * omitted from SARIF result message text. Use this when the consuming
         * system already renders the {@code properties} bag and the extra text is
         * unwanted. Default: {@code false} (scores are embedded in messages).
         */
        @JsonProperty("sarifOmitScores")
        /* default */ boolean sarifOmitScores;

        /**
         * Minimum AI confidence score (inclusive) required for a method to be
         * emitted. Methods whose {@code ai_confidence} is below this threshold
         * are silently dropped. Only meaningful when {@code ai.confidence: true}
         * is also set. Default: {@code 0.0} (no filtering).
         */
        @JsonProperty("minConfidence")
        /* default */ Double minConfidence;

        /**
         * When {@code true}, enable credential detection in addition to the normal
         * test-method scan. Default: {@code false}.
         */
        @JsonProperty("detectSecrets")
        /* default */ boolean detectSecrets;

        /**
         * Glob pattern overriding the default test-file mask when scanning for
         * secrets. {@code null} means use the default mask derived from
         * {@code fileSuffixes}.
         */
        @JsonProperty("secretsInclude")
        /* default */ String secretsInclude;

        /**
         * Path to a custom rule catalog YAML file. {@code null} uses the built-in
         * catalog bundled with the detect-secrets module.
         */
        @JsonProperty("secretsRules")
        /* default */ String secretsRules;

        /**
         * Output path for the secrets CSV. {@code null} causes the default
         * {@code methodatlas-credentials.csv} in the current working directory to be
         * used.
         */
        @JsonProperty("secretsOut")
        /* default */ String secretsOut;

        /**
         * When {@code true}, force a standalone triage LLM call instead of
         * appending the secret-triage prompt to the normal test-classification
         * call. Default: {@code false}.
         */
        @JsonProperty("secretsSeparateLlm")
        /* default */ boolean secretsSeparateLlm;

        /**
         * When {@code true}, print unmasked secret values in CSV and SARIF output.
         * Default: {@code false} (values are redacted).
         */
        @JsonProperty("secretsShowValues")
        /* default */ boolean secretsShowValues;

        /**
         * SARIF error score floor. Findings at or above this value are emitted as
         * {@code error}-level SARIF results. Default: {@code 0.8}.
         */
        @JsonProperty("secretsErrorThreshold")
        /* default */ Double secretsErrorThreshold;

        /**
         * SARIF warning score floor. Findings at or above this value (but below
         * {@code secretsErrorThreshold}) are emitted as {@code warning}-level SARIF
         * results. Default: {@code 0.4}.
         */
        @JsonProperty("secretsWarningThreshold")
        /* default */ Double secretsWarningThreshold;

        /**
         * Suppress findings whose triage score is below this value. Default:
         * {@code 0.0} keeps all findings.
         */
        @JsonProperty("secretsMinScore")
        /* default */ Double secretsMinScore;

        /** AI enrichment settings. */
        @JsonProperty("ai")
        /* default */ YamlAiConfig ai;
    }

    /**
     * AI subsystem configuration within the YAML file.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    /* default */ static final class YamlAiConfig {

        /** Whether AI enrichment is enabled. */
        @JsonProperty("enabled")
        /* default */ Boolean enabled;

        /**
         * AI provider: {@code auto}, {@code ollama}, {@code openai},
         * {@code openrouter}, {@code anthropic}, {@code azure_openai},
         * {@code groq}, {@code xai}, {@code github_models}, or {@code mistral}.
         */
        @JsonProperty("provider")
        /* default */ String provider;

        /** Provider-specific model name. */
        @JsonProperty("model")
        /* default */ String model;

        /** Provider base URL override. */
        @JsonProperty("baseUrl")
        /* default */ String baseUrl;

        /** API key supplied directly. */
        @JsonProperty("apiKey")
        /* default */ String apiKey;

        /** Name of the environment variable that holds the API key. */
        @JsonProperty("apiKeyEnv")
        /* default */ String apiKeyEnv;

        /** Path to an external taxonomy file. */
        @JsonProperty("taxonomyFile")
        /* default */ String taxonomyFile;

        /** Built-in taxonomy variant: {@code default} or {@code optimized}. */
        @JsonProperty("taxonomyMode")
        /* default */ String taxonomyMode;

        /** Maximum number of characters of class source sent to the AI. */
        @JsonProperty("maxClassChars")
        /* default */ Integer maxClassChars;

        /** AI request timeout in seconds. */
        @JsonProperty("timeoutSec")
        /* default */ Long timeoutSec;

        /** Maximum number of retries for AI requests. */
        @JsonProperty("maxRetries")
        /* default */ Integer maxRetries;

        /** Whether to request a confidence score for each classification. */
        @JsonProperty("confidence")
        /* default */ Boolean confidence;

        /**
         * Azure OpenAI REST API version appended as the {@code api-version} query
         * parameter; only used when {@code provider: azure_openai} is set.
         */
        @JsonProperty("apiVersion")
        /* default */ String apiVersion;

        /** Path to a custom method-classification prompt template (default: built-in). */
        @JsonProperty("classificationPrompt")
        /* default */ String classificationPrompt;

        /** Path to a custom folded credential-triage appendix template (default: built-in). */
        @JsonProperty("triagePrompt")
        /* default */ String triagePrompt;

        /** Path to a custom standalone credential-triage template (default: built-in). */
        @JsonProperty("dedicatedTriagePrompt")
        /* default */ String dedicatedTriagePrompt;
    }
}
