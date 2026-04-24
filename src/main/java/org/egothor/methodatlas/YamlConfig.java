package org.egothor.methodatlas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

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
 * outputMode: csv          # csv | plain | sarif  (default: csv)
 * emitMetadata: false      # (default: false)
 * contentHash: false       # (default: false)
 * securityOnly: false      # (default: false)
 * driftDetect: false       # (default: false)
 * overrideFile: .methodatlas-overrides.yaml  # optional
 * fileSuffixes:
 *   - Test.java
 * testAnnotations:
 *   - Test
 *   - ParameterizedTest
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
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(configFile.toFile(), YamlConfigFile.class);
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

        /** Annotation simple names that identify test methods. */
        @JsonProperty("testAnnotations")
        /* default */ List<String> testAnnotations;

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
         * When {@code true}, a {@code tag_ai_drift} column is added to CSV/plain
         * output comparing the source-level {@code @Tag("security")} annotation
         * against the AI security-relevance classification.
         */
        @JsonProperty("driftDetect")
        /* default */ boolean driftDetect;

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
    }
}
