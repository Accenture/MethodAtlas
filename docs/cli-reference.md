# CLI reference

## Synopsis

```bash
./methodatlas [options] [path ...]
```

If no scan path is provided, the current directory is scanned. Multiple root paths are supported.

## General options

| Argument | Meaning | Default |
| --- | --- | --- |
| `-config <file>` | Load default option values from a YAML configuration file; command-line flags override YAML values | — |
| `-plain` | Emit plain text instead of CSV | CSV mode |
| `-sarif` | Emit SARIF 2.1.0 JSON instead of CSV | CSV mode |
| `-emit-metadata` | Prepend `# key: value` comment lines before the CSV header | Off |
| `-file-suffix <suffix>` | Include files whose name ends with `suffix`; may be repeated; first occurrence replaces the default | `Test.java` |
| `-test-annotation <name>` | Treat methods carrying annotation `name` as test methods; may be repeated; first occurrence replaces the default set | `Test`, `ParameterizedTest`, `RepeatedTest`, `TestFactory`, `TestTemplate` |
| `-content-hash` | Append a SHA-256 fingerprint of each class source to every emitted record | Off |
| `-apply-tags` | Write AI-generated `@DisplayName` and `@Tag` annotations back to the scanned source files; requires AI to be enabled | Off |
| `[path ...]` | One or more root paths to scan | Current directory |

## AI options

| Argument | Meaning | Default |
| --- | --- | --- |
| `-ai` | Enable AI enrichment | Off |
| `-ai-confidence` | Ask the model to include a confidence score (`0.0–1.0`) per classification | Off |
| `-ai-provider <provider>` | Select provider: `auto`, `ollama`, `openai`, `openrouter`, `anthropic` | `auto` |
| `-ai-model <model>` | Provider-specific model identifier | `qwen2.5-coder:7b` |
| `-ai-base-url <url>` | Override provider base URL | Provider default |
| `-ai-api-key <key>` | Supply API key directly on the command line | — |
| `-ai-api-key-env <name>` | Read API key from an environment variable | — |
| `-ai-taxonomy <path>` | Load taxonomy text from an external file | Built-in taxonomy |
| `-ai-taxonomy-mode <mode>` | Select built-in taxonomy variant: `default` or `optimized` | `default` |
| `-ai-max-class-chars <count>` | Skip AI for classes larger than this character count | `40000` |
| `-ai-timeout-sec <seconds>` | Request timeout for provider calls | `90` |
| `-ai-max-retries <count>` | Retry limit for AI operations | `1` |

## Manual AI options

| Argument | Meaning |
| --- | --- |
| `-manual-prepare <workdir> <responsedir>` | Write AI prompt work files to `workdir` and empty response stubs to `responsedir`; no CSV output |
| `-manual-consume <workdir> <responsedir>` | Read operator-filled response files from `responsedir` and emit the enriched CSV |

The two directory arguments may be the same path.

## Option details

### `-config <file>`

Loads default option values from a YAML configuration file before processing any other arguments. Command-line flags always take precedence over values from the file.

```yaml
outputMode: sarif          # csv | plain | sarif  (default: csv)
emitMetadata: false
contentHash: false         # append SHA-256 fingerprint column  (default: false)
fileSuffixes:
  - Test.java
  - IT.java
testAnnotations:
  - Test
  - ParameterizedTest
ai:
  enabled: true
  provider: ollama
  model: qwen2.5-coder:7b
  baseUrl: http://localhost:11434
  apiKey: sk-...
  apiKeyEnv: MY_API_KEY_ENV
  taxonomyFile: /path/to/taxonomy.txt
  taxonomyMode: default       # default | optimized
  maxClassChars: 40000
  timeoutSec: 90
  maxRetries: 1
  confidence: false
```

All fields are optional. Unknown fields are silently ignored. This makes it safe to add future fields to a shared configuration file without breaking older versions.

A configuration file is useful in CI pipelines or when several team members share the same scan settings. Individual developers can override specific values on the command line without editing the shared file.

### `-plain`

Switches output from CSV to a human-readable line-oriented format. Method discovery and AI classification are unaffected.

### `-sarif`

Switches output to a single [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html) JSON document. MethodAtlas buffers all discovered test methods and serializes the complete document after the scan finishes, so the JSON is valid even when the scan spans many files.

Security-relevant methods receive SARIF level `note`; all other methods receive level `none`. Rule IDs are derived from AI tags (e.g. `security/auth`, `security/crypto`). Non-security methods use the rule `test-method`.

See [output-formats.md](output-formats.md#sarif-mode) for the full schema description and an example document.

### `-emit-metadata`

Prepends `# key: value` comment lines before the CSV header:

```text
# tool_version: 1.2.0
# scan_timestamp: 2025-04-09T10:15:30Z
# taxonomy: built-in/default
fqcn,method,loc,tags,...
```

Standard CSV parsers treat `#`-prefixed lines as comments and skip them. The lines are useful for archiving scan results with provenance information.

### `-file-suffix <suffix>`

Filters which files are considered test classes. The flag may be repeated to match multiple patterns:

```bash
./methodatlas -file-suffix Test.java -file-suffix IT.java /path/to/tests
```

The first occurrence replaces the built-in default (`Test.java`). Each subsequent occurrence adds an additional pattern.

### `-test-annotation <name>`

Extends or replaces the set of annotation simple names that MethodAtlas uses to identify JUnit test methods. The default set is `Test`, `ParameterizedTest`, `RepeatedTest`, `TestFactory`, and `TestTemplate`.

The first occurrence of `-test-annotation` replaces the entire default set; subsequent occurrences append to it:

```bash
# Recognise only @Test and @MyCustomTest
./methodatlas -test-annotation Test -test-annotation MyCustomTest /path/to/tests
```

Annotation matching is performed against the simple name only (symbol resolution is not available in source-only parsing mode). False positives are possible if a project defines a custom annotation with the same simple name as a JUnit Jupiter annotation.

### `-content-hash`

Appends a SHA-256 content fingerprint to every emitted record. The hash is computed from the JavaParser AST string representation of the enclosing class, so it is independent of file encoding, line endings, and unrelated file-level changes. When a class contains multiple test methods, all of them share the same hash value.

In CSV output, a `content_hash` column is appended immediately after `tags`:

```text
fqcn,method,loc,tags,content_hash
com.acme.tests.SampleOneTest,alpha,8,fast;crypto,3a7f9b...
com.acme.tests.SampleOneTest,beta,6,param,3a7f9b...
```

In plain-text output, a `HASH=<value>` token is appended to each line. In SARIF output, the hash is stored as `properties.contentHash`.

The flag can also be enabled via YAML configuration:

```yaml
contentHash: true
```

A command-line `-content-hash` flag always overrides the YAML setting.

**Use cases:**

- **Incremental scanning** — compare hashes across runs to skip classes that have not changed.
- **Result traceability** — correlate a SARIF finding back to the exact class revision that produced it.
- **Change detection in CI** — detect when a class is modified between two pipeline runs without diffing source files.

### `-apply-tags`

Instead of emitting a report, modifies source files in place by inserting AI-generated `@DisplayName` and `@Tag` annotations on security-relevant test methods. Requires AI to be enabled via `-ai` or `-manual-consume`.

A summary line is always printed to standard output:

```text
Apply-tags complete: 12 annotation(s) added to 3 file(s)
```

See [ai-guide.md](ai-guide.md#apply-tags-workflow) for the complete workflow and formatting guarantees.

### `-ai`

Enables AI enrichment. Without this flag, MethodAtlas behaves as a pure static scanner. When present, a suggestion engine is initialized before scanning begins.

### `-ai-confidence`

Instructs the model to include a confidence score for each classification. The score appears as `ai_confidence` in CSV output (or `AI_CONFIDENCE=` in plain mode). Scores range from `0.0` (not security-relevant) to `1.0` (explicitly and unambiguously tests a named security property). See [ai-guide.md](ai-guide.md#ai-confidence-scoring) for the full interpretation table.

### `-ai-provider <provider>`

Selects the provider implementation. Values are case-insensitive.

| Value | Behavior |
| --- | --- |
| `auto` | Probes local Ollama first; falls back to an API-key provider if Ollama is unreachable |
| `ollama` | Local Ollama inference at `http://localhost:11434` |
| `openai` | OpenAI API |
| `openrouter` | OpenRouter API |
| `anthropic` | Anthropic API |

### `-ai-model <model>`

Specifies the model name passed to the provider. For Ollama the name must match a downloaded model. For hosted providers it is the model identifier returned by the provider's API.

### `-ai-base-url <url>`

Overrides the provider's default base URL. Useful for self-hosted gateways, proxies, or non-default local deployments.

| Provider | Built-in default |
| --- | --- |
| `auto` / `ollama` | `http://localhost:11434` |
| `openai` | `https://api.openai.com` |
| `openrouter` | `https://openrouter.ai/api` |
| `anthropic` | `https://api.anthropic.com` |

### `-ai-api-key <key>`

Provides the API key directly on the command line. Takes precedence over `-ai-api-key-env`. Prefer the environment variable form in scripts to avoid leaking secrets into shell history.

### `-ai-api-key-env <name>`

Reads the API key from a named environment variable:

```bash
export OPENROUTER_API_KEY=sk-...
./methodatlas -ai -ai-provider openrouter -ai-api-key-env OPENROUTER_API_KEY /path/to/tests
```

If neither `-ai-api-key` nor `-ai-api-key-env` is provided, providers that require hosted authentication will be unavailable.

### `-ai-taxonomy <path>`

Loads taxonomy text from an external file instead of the built-in taxonomy. The file contents replace the taxonomy text verbatim. When combined with `-ai-taxonomy-mode`, the external file takes precedence.

### `-ai-taxonomy-mode <mode>`

Selects a built-in taxonomy variant:

- `default` — descriptive, human-readable; suitable for teams building familiarity with the taxonomy
- `optimized` — compact; reduces prompt size and tends to improve classification reliability with smaller models

### `-ai-max-class-chars <count>`

Sets a ceiling on the serialized class size eligible for AI analysis. If a class source exceeds this number of characters, AI classification is skipped for that class; the scan continues and the row is emitted with blank AI columns.

### `-ai-timeout-sec <seconds>`

Sets the timeout applied to each AI provider request. The default is 90 seconds.

### `-ai-max-retries <count>`

Sets the retry limit for failed AI operations. The default is 1.

### `-manual-prepare <workdir> <responsedir>`

Runs the prepare phase of the manual AI workflow. For each test class MethodAtlas writes a work file containing the AI prompt and creates an empty response placeholder. No CSV is emitted. See [ai-guide.md](ai-guide.md#manual-ai-workflow) for the full workflow.

### `-manual-consume <workdir> <responsedir>`

Runs the consume phase. MethodAtlas reads operator-filled response files and merges the AI JSON into the output CSV. Missing or empty response files are treated as absent AI data; the scan continues.

For practical examples grouped by use case, see [CLI Examples](cli-examples.md).
