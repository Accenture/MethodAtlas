# CLI reference

## Synopsis

```bash
./methodatlas [options] [path ...]
```

If no scan path is provided, the current directory is scanned. Multiple root paths are supported.

## General options

| Argument | Meaning | Default |
| --- | --- | --- |
| `-plain` | Emit plain text instead of CSV | CSV mode |
| `-emit-metadata` | Prepend `# key: value` comment lines before the CSV header | Off |
| `-file-suffix <suffix>` | Include files whose name ends with `suffix`; may be repeated; first occurrence replaces the default | `Test.java` |
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

### `-plain`

Switches output from CSV to a human-readable line-oriented format. Method discovery and AI classification are unaffected.

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

## Example commands

### Static scan only

```bash
./methodatlas /path/to/project
```

### Plain text output

```bash
./methodatlas -plain /path/to/project
```

### Scan with metadata header

```bash
./methodatlas -emit-metadata /path/to/project
```

### Include integration test files

```bash
./methodatlas -file-suffix Test.java -file-suffix IT.java /path/to/project
```

### AI with local Ollama

```bash
./methodatlas -ai -ai-provider ollama -ai-model qwen2.5-coder:7b /path/to/tests
```

### AI with confidence scoring (Ollama)

```bash
./methodatlas -ai -ai-confidence -ai-provider ollama /path/to/tests
```

### AI with OpenRouter

```bash
export OPENROUTER_API_KEY=sk-...
./methodatlas -ai \
  -ai-provider openrouter \
  -ai-api-key-env OPENROUTER_API_KEY \
  -ai-model stepfun/step-3.5-flash:free \
  /path/to/tests
```

### AI with Anthropic

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./methodatlas -ai \
  -ai-provider anthropic \
  -ai-api-key-env ANTHROPIC_API_KEY \
  -ai-model claude-3-haiku-20240307 \
  /path/to/tests
```

### Automatic provider selection

```bash
./methodatlas -ai /path/to/tests
```

### Compact taxonomy for smaller models

```bash
./methodatlas -ai -ai-taxonomy-mode optimized /path/to/tests
```

### Filter high-confidence findings

```bash
./methodatlas -ai -ai-confidence /path/to/tests | \
  awk -F',' 'NR==1 || ($9+0) >= 0.7'
```

### Manual AI workflow

```bash
# Phase 1 — write prompts
./methodatlas -manual-prepare ./work ./responses /path/to/tests

# (paste each work file's AI PROMPT block into a chat window
#  and save the response into the corresponding .response.txt file)

# Phase 2 — consume responses and emit CSV
./methodatlas -manual-consume ./work ./responses /path/to/tests
```

## Real-world output example

Running against a mix of functional and cryptographic test classes:

```bash
./methodatlas -ai \
  -ai-provider openrouter \
  -ai-api-key-env OPENROUTER_API_KEY \
  -ai-model stepfun/step-3.5-flash:free \
  path/to/tests/
```

Produces output such as:

```csv
fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason
org.egothor.methodatlas.MethodAtlasAppTest,csvMode_detectsMethodsLocAndTags,22,,false,,,Test verifies functional output format only.
zeroecho.core.alg.aes.AesGcmCrossCheckTest,aesGcm_stream_vs_jca_ctxOnly_crosscheck,52,,true,SECURITY: crypto - cross-check AES-GCM stream encryption with JCA reference,security;crypto,Verifies custom AES-GCM matches JCA output — ensures cryptographic correctness.
zeroecho.core.alg.aes.AesLargeDataTest,aesGcmLargeData_ctxOnly,27,,true,SECURITY: crypto - AES-GCM round-trip with context-only parameters,security;crypto,Tests encryption and decryption correctness for large data using AES-GCM.
zeroecho.core.alg.mldsa.MldsaLargeDataTest,mldsa_complete_suite_streaming_sign_verify_large_data,24,,true,SECURITY: crypto - ML-DSA streaming signature and verification for large data,security;crypto;owasp,Validates ML-DSA signature creation and verification including tamper detection.
```

Observations:

- Functional tests are left untagged.
- Cryptographic tests are detected and tagged consistently with `security;crypto`.
- Suggested display names are ready to use as `@DisplayName` values.
- The `ai_reason` column makes the classification defensible during review.
