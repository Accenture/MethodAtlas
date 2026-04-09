# MethodAtlasApp

<img src="MethodAtlas.png" width="20%" align="right" alt="MethodAtlas logo" />

MethodAtlas is a small standalone CLI that scans Java source trees for JUnit 5 test methods and emits one record per discovered method.

The tool combines **deterministic source analysis** with optional **AI-assisted classification** so that developers can quickly understand what a test suite contains and which tests appear security-relevant.

Unlike tools that rely entirely on large language models or agent pipelines, MethodAtlas separates the problem into two parts:

- **Deterministic discovery** — a Java AST parser determines exactly which test methods exist
- **AI interpretation** — an optional model classifies those methods and suggests security-related annotations

This approach keeps the analysis **predictable, reproducible, and reviewable**, while still benefiting from AI where it adds value.

The parser determines *what exists* in the code.  
The AI suggests *what it means*.

## What MethodAtlas reports

For each discovered JUnit test method, MethodAtlas emits a single record containing:

- `fqcn` – fully qualified class name
- `method` – test method name
- `loc` – inclusive lines of code for the method declaration
- `tags` – existing JUnit `@Tag` values declared on the method

When AI enrichment is enabled, additional fields are included:

- `ai_security_relevant` – whether the model classified the test as security-relevant
- `ai_display_name` – suggested security-oriented `@DisplayName`
- `ai_tags` – suggested security taxonomy tags
- `ai_reason` – short rationale for the classification

These suggestions help identify tests that verify authentication, access control, cryptography, input validation, or other security-relevant behavior.

## Deterministic method discovery

Test discovery is performed using **JavaParser** and the Java AST rather than regex scanning or LLM inference.

The CLI:

- scans files matching `*Test.java`
- detects JUnit Jupiter methods annotated with  
  `@Test`, `@ParameterizedTest`, or `@RepeatedTest`
- extracts existing tags from both repeated `@Tag` usage and `@Tags({...})`

Because the list of test methods is obtained from the AST, the analysis is **deterministic and reproducible** regardless of the AI provider used for classification.

## AI-assisted security classification

If AI mode is enabled, MethodAtlas sends the **full class source for context** together with the **exact list of parser-discovered test methods**.

The model is asked to classify only those methods and suggest:

- whether the test appears security-relevant
- consistent security taxonomy tags
- a meaningful security-oriented display name

This design avoids relying on AI to infer program structure and instead uses it only for semantic interpretation.

MethodAtlas supports multiple providers and can also run against **locally hosted models via Ollama**, allowing teams to use AI without exposing proprietary source code.

MethodAtlas is designed to be lightweight, deterministic, and easy to integrate into developer workflows or CI pipelines.

## Distribution layout

After building and packaging, the distribution archive has this structure:

```text
methodatlas-<version>/
├── bin/
│   ├── methodatlas
│   └── methodatlas.bat
└── lib/
    └── methodatlas-<version>.jar
```

Run the CLI from the `bin` directory, for example:

```bash
cd methodatlas-<version>/bin
./methodatlas /path/to/project
```

## Usage

```bash
./methodatlas [options] [path1] [path2] ...
```

If no scan path is provided, the current directory is scanned. Multiple root paths are supported.

## Output modes

### CSV mode (default)

CSV mode prints a header followed by one record per discovered test method.

Without AI:

```text
fqcn,method,loc,tags
```

With AI:

```text
fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason
```

Example:

```text
fqcn,method,loc,tags
com.acme.tests.SampleOneTest,alpha,8,fast;crypto
com.acme.tests.SampleOneTest,beta,6,param
com.acme.tests.SampleOneTest,gamma,4,nested1;nested2
com.acme.other.AnotherTest,delta,3,
```

### Plain mode

Enable plain mode with `-plain`:

```bash
./methodatlas -plain /path/to/project
```

Plain mode renders one line per method:

```text
com.acme.tests.SampleOneTest, alpha, LOC=8, TAGS=fast;crypto
com.acme.tests.SampleOneTest, beta, LOC=6, TAGS=param
com.acme.tests.SampleOneTest, gamma, LOC=4, TAGS=nested1;nested2
com.acme.other.AnotherTest, delta, LOC=3, TAGS=-
```

If a method has no source-level JUnit tags, plain mode prints `TAGS=-`.

## AI enrichment

When AI support is enabled, MethodAtlas submits each parsed test class to a provider-agnostic suggestion engine and merges returned method-level suggestions into the emitted output.

The AI subsystem can:

- classify whether a test is security-relevant
- propose a `SECURITY: ...` display name
- assign controlled taxonomy tags
- provide a short rationale

Supported providers:

- `auto`
- `ollama`
- `openai`
- `openrouter`
- `anthropic`

In `auto` mode, MethodAtlas prefers a reachable local Ollama instance and otherwise falls back to an OpenAI-compatible provider when an API key is configured.

## Manual AI workflow

Some teams have access to an AI chat window (ChatGPT, Claude, Copilot, etc.) but cannot reach an AI API directly — for example, because corporate security policy blocks outbound API calls or because there is no budget for API credentials.

MethodAtlas supports this scenario through a two-phase **manual AI workflow** that replaces automated API communication with operator-mediated interaction.

### How it works

```
Phase 1 – Prepare                         Phase 2 – Consume
──────────────────────────────────────    ──────────────────────────────────────
./methodatlas                             ./methodatlas
  -manual-prepare ./work ./responses        -manual-consume ./work ./responses
  /path/to/tests                            /path/to/tests

  ↓                                         ↓
For each test class:                      For each test class:
  write  work/<fqcn>.txt                    look for responses/<fqcn>.response.txt
  create responses/<fqcn>.response.txt      → non-empty: parse AI JSON → AI cols
  (operator instructions + AI prompt)       → empty/absent: blank AI columns
```

The two directory arguments may point to the same path if you prefer a single folder.

The final CSV format is identical to the automated flow.

### Phase 1 — Prepare

```bash
./methodatlas -manual-prepare ./work ./responses /path/to/tests
```

MethodAtlas scans the source tree and for each test class writes:

1. **`<workdir>/<fqcn>.txt`** — the work file, containing:
   - **Operator instructions** at the top: what to do, the expected response file name, and the consume command to run afterwards.
   - **AI prompt block**: the complete prompt (taxonomy definition, method list, full class source) delimited by `--- BEGIN AI PROMPT ---` / `--- END AI PROMPT ---` markers.
2. **`<responsedir>/<fqcn>.response.txt`** — an empty placeholder for the AI response. If this file already exists (e.g. from a previous prepare run where a response was already saved), it is left untouched.

Both directories are created automatically if they do not exist. They may be the same path.

The operator copies the prompt block verbatim into their AI chat window, then pastes the AI's response into the pre-created `.response.txt` file. No file attachments or manual file creation are needed.

No CSV output is produced in this phase.

### Phase 2 — Consume

After pasting the AI responses into the pre-created `.response.txt` files:

```bash
./methodatlas -manual-consume ./work ./responses /path/to/tests
```

Pass the same response directory you specified in the prepare phase.

For each test class, MethodAtlas reads `<fully.qualified.ClassName>.response.txt` from the response directory:

- **File contains AI JSON** — MethodAtlas extracts the first JSON object it finds (surrounding prose from the chat window is silently ignored), parses it, and merges the AI suggestions into the output.
- **File is empty or absent** — MethodAtlas emits the row with blank AI columns. The scan continues; missing or empty responses are not an error.

This makes it easy to process batches incrementally: run consume after filling in each response rather than waiting until all classes are done.

### Work file format

Each work file looks like this:

```
================================================================================
OPERATOR INSTRUCTIONS
================================================================================
Class      : com.acme.security.AccessControlServiceTest
Work file  : com.acme.security.AccessControlServiceTest.txt
Response   : com.acme.security.AccessControlServiceTest.response.txt

Steps:
  1. Copy the AI PROMPT block below (between the BEGIN/END markers)
     into your AI chat window.
  2. Wait for the AI to respond.
  3. Paste the complete AI response into the pre-created file:
       com.acme.security.AccessControlServiceTest.response.txt
     (it was created empty alongside this work file — do not rename it).
  4. Repeat for all other work files.
  5. After all responses are saved, run the consume phase:
       java -jar methodatlas.jar -manual-consume <workdir> <responsedir> <source-roots...>
================================================================================

--- BEGIN AI PROMPT ---
You are analyzing a single JUnit 5 test class...
(full prompt with taxonomy, method list, and class source)
--- END AI PROMPT ---
```

### Taxonomy in manual mode

The same taxonomy configuration flags used for automated providers also apply to the manual workflow. Pass `-ai-taxonomy <file>` or `-ai-taxonomy-mode optimized` before `-manual-prepare` to control what taxonomy text is embedded in the prompt.

```bash
./methodatlas \
  -ai-taxonomy-mode optimized \
  -manual-prepare ./work ./responses \
  /path/to/tests
```

## Complete command-line arguments

### General options

| Argument | Meaning | Default |
| --- | --- | --- |
| `-plain` | Emit plain text instead of CSV | CSV mode |
| `-file-suffix <suffix>` | Include files whose name ends with `suffix`; may be repeated to match multiple patterns; first occurrence replaces the default | `Test.java` |
| `[path ...]` | One or more root paths to scan | Current directory |

### AI options

| Argument | Meaning | Notes / default |
| --- | --- | --- |
| `-ai` | Enable AI enrichment | Disabled by default |
| `-ai-provider <provider>` | Select provider | `auto`, `ollama`, `openai`, `openrouter`, `anthropic` |
| `-ai-model <model>` | Provider-specific model identifier | Default is `qwen2.5-coder:7b` |
| `-ai-base-url <url>` | Override provider base URL | Provider-specific default URL is used otherwise |
| `-ai-api-key <key>` | Supply API key directly on the command line | Useful for quick experiments; env vars are often preferable |
| `-ai-api-key-env <name>` | Read API key from an environment variable | Used if `-ai-api-key` is not supplied |
| `-ai-taxonomy <path>` | Load taxonomy text from an external file | Overrides built-in taxonomy text |
| `-ai-taxonomy-mode <mode>` | Select built-in taxonomy mode | `default` or `optimized`; default is `default` |
| `-ai-max-class-chars <count>` | Skip AI analysis for larger classes | Default is `40000` |
| `-ai-timeout-sec <seconds>` | Set request timeout for provider calls | Default is `90` seconds |
| `-ai-max-retries <count>` | Set retry limit for AI operations | Default is `1` |

### Manual AI options

| Argument | Meaning |
| --- | --- |
| `-manual-prepare <workdir> <responsedir>` | Run the prepare phase: write AI prompt work files to `workdir` and empty response stubs to `responsedir`; the two paths may be the same; no CSV output |
| `-manual-consume <workdir> <responsedir>` | Run the consume phase: read operator-filled response files from `responsedir` and emit the enriched CSV |

Unknown options cause an error. Missing option values also fail fast.

### Argument details

#### `-plain`

Switches output rendering from CSV to a human-readable line-oriented format. This affects rendering only; method discovery and AI classification behavior remain the same.

#### `-ai`

Turns on AI enrichment. Without this flag, MethodAtlas behaves as a pure static scanner and emits only source-derived metadata. When this flag is present, the application initializes an AI suggestion engine before scanning.

#### `-ai-provider <provider>`

Selects the provider implementation.

Accepted values are case-insensitive because the CLI normalizes them internally before mapping them to the provider enum. Available providers are:

- `auto`
- `ollama`
- `openai`
- `openrouter`
- `anthropic`

`auto` is the default.

#### `-ai-model <model>`

Specifies the provider-specific model name. Examples include local Ollama model names or hosted model identifiers accepted by OpenAI-compatible providers. The default is `qwen2.5-coder:7b`.

#### `-ai-base-url <url>`

Overrides the provider base URL.

If omitted, MethodAtlas uses these defaults:

| Provider | Default base URL |
| --- | --- |
| `auto` | `http://localhost:11434` |
| `ollama` | `http://localhost:11434` |
| `openai` | `https://api.openai.com` |
| `openrouter` | `https://openrouter.ai/api` |
| `anthropic` | `https://api.anthropic.com` |

This is useful for self-hosted gateways, proxies, compatible endpoints, or non-default local deployments.

#### `-ai-api-key <key>`

Provides the API key directly. This takes precedence over `-ai-api-key-env` because the resolved API key logic first checks the explicit key and only then consults the environment variable.

#### `-ai-api-key-env <name>`

Reads the API key from an environment variable such as:

```bash
export OPENROUTER_API_KEY=...
./methodatlas -ai -ai-provider openrouter -ai-api-key-env OPENROUTER_API_KEY /path/to/tests
```

If both `-ai-api-key` and `-ai-api-key-env` are omitted, providers that require hosted authentication will be unavailable.

#### `-ai-taxonomy <path>`

Loads taxonomy text from an external file instead of using the built-in taxonomy. This lets you tailor classification categories or rules to your own security testing conventions.

#### `-ai-taxonomy-mode <mode>`

Selects one of the built-in taxonomy variants:

- `default` — more descriptive, human-readable taxonomy
- `optimized` — more compact taxonomy intended to improve model reliability and reduce prompt size

When `-ai-taxonomy` is also supplied, the external taxonomy file takes precedence.

#### `-ai-max-class-chars <count>`

Sets the maximum serialized class size eligible for AI analysis. If a class source exceeds this number of characters, MethodAtlas skips AI classification for that class and continues scanning normally.

#### `-ai-timeout-sec <seconds>`

Configures the timeout applied to AI provider requests. The default is 90 seconds.

#### `-ai-max-retries <count>`

Configures the retry count retained in AI runtime options. The current default is `1`.

## Example commands

Basic scan:

```bash
./methodatlas /path/to/project
```

Plain output:

```bash
./methodatlas -plain /path/to/project
```

AI with OpenRouter and direct API key:

```bash
./methodatlas -ai -ai-provider openrouter -ai-api-key YOUR_API_KEY -ai-model stepfun/step-3.5-flash:free /path/to/junit/tests
```

AI with OpenRouter and environment variable:

```bash
export OPENROUTER_API_KEY=YOUR_API_KEY
./methodatlas -ai -ai-provider openrouter -ai-api-key-env OPENROUTER_API_KEY -ai-model stepfun/step-3.5-flash:free /path/to/junit/tests
```

AI with local Ollama:

```bash
./methodatlas -ai -ai-provider ollama -ai-model qwen2.5-coder:7b /path/to/junit/tests
```

Automatic provider selection:

```bash
./methodatlas -ai /path/to/junit/tests
```

## Highlighted example: AI extension in action

In a real packaged setup, running MethodAtlas from the unzipped distribution against a subset of MethodAtlas and ZeroEcho test sources with:

```bash
./methodatlas -ai -ai-provider openrouter -ai-api-key OBTAIN_YOUR_API_KEY -ai-model stepfun/step-3.5-flash:free some/dir/with/junit/tests/
```

produced output such as:

```csv
fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason
org.egothor.methodatlas.MethodAtlasAppTest,csvMode_detectsMethodsLocAndTags,22,,false,,,"Test verifies functional output format and data extraction of MethodAtlasApp, not security properties."
org.egothor.methodatlas.MethodAtlasAppTest,plainMode_detectsMethodsLocAndTags,20,,false,,,"Test verifies functional output format and data extraction of MethodAtlasApp, not security properties."
zeroecho.core.alg.aes.AesGcmCrossCheckTest,aesGcm_stream_vs_jca_ctxOnly_crosscheck,52,,true,SECURITY: crypto - cross-check AES-GCM stream encryption with JCA reference,security;crypto,"The test verifies that the custom AES-GCM stream implementation produces identical ciphertexts and plaintexts as the JCA reference, ensuring cryptographic correctness and preventing failures that could lead to loss of confidentiality or integrity."
zeroecho.core.alg.aes.AesLargeDataTest,aesGcmLargeData_ctxOnly,27,,true,SECURITY: crypto - AES-GCM round-trip with context-only parameters,security;crypto,"Tests encryption and decryption correctness for large data using AES-GCM, ensuring the authenticated encryption mechanism functions properly for confidentiality and integrity."
zeroecho.core.alg.aes.AesLargeDataTest,aesGcmLargeData_headerCodec,29,,true,SECURITY: crypto - AES-GCM round-trip with header codec,security;crypto,"Validates AES-GCM with an in-band header codec, confirming correct handling of additional authenticated data in the encryption process."
zeroecho.core.alg.aes.AesLargeDataTest,aesCbcPkcs5LargeData_ctxOnly,27,,true,SECURITY: crypto - AES-CBC/PKCS7Padding round-trip with context-only IV,security;crypto,"Ensures AES-CBC encryption and decryption with PKCS7 padding works correctly for large data, testing confidentiality without integrity protection."
zeroecho.core.alg.mldsa.MldsaLargeDataTest,mldsa_complete_suite_streaming_sign_verify_large_data,24,,true,SECURITY: crypto - ML-DSA streaming signature and verification for large data with integrity check,security;crypto;owasp,"Validates cryptographic correctness of ML-DSA signature creation and verification, including handling large data streams, signature length checks, and rejection of tampered signatures via bit-flip, ensuring data integrity and resistance to forgery."
```

What this shows in practice:

- Functional tests remain untouched.
- Security-relevant cryptographic tests are detected correctly.
- The tool suggests consistent taxonomy tags such as `security`, `crypto`, and, where appropriate, `owasp`.
- The generated display names are already suitable as candidate `@DisplayName` values.
- The rationale column explains why a method was classified as security-relevant.

For a programmer, this turns a raw test tree into a searchable, structured inventory of security tests without requiring manual tagging of every method.

## Built-in security taxonomy

The prompt builder enforces a closed tag set so that providers do not invent categories. The built-in taxonomy covers these security areas:

- `auth`
- `access-control`
- `crypto`
- `input-validation`
- `injection`
- `data-protection`
- `logging`
- `error-handling`
- `owasp`

Every security-relevant method must include the umbrella tag `security`, and suggested display names should follow:

```text
SECURITY: <security property> - <scenario>
```

MethodAtlas ships both a default taxonomy and a more compact optimized taxonomy.

## Why this is useful

MethodAtlas is useful when you need to:

- inventory a large JUnit suite quickly
- find tests that already validate security properties
- identify where security tagging is inconsistent or missing
- export structured metadata for reporting, dashboards, or CI jobs
- review security test coverage before an audit or release

Because the application emits one row per test method, the output is easy to pipe into shell scripts, spreadsheets, data pipelines, or further static analysis.

## Notes

- The scanner considers files ending with `*Test.java` by default. Use `-file-suffix` to override or extend this. The flag may be repeated to match multiple patterns, e.g. `-file-suffix Test.java -file-suffix IT.java`. The first occurrence replaces the default; subsequent occurrences add further patterns.
- AI classification is class-contextual: the full class source is submitted so the model can classify methods with more context.
- If AI support is enabled but engine initialization fails, the application aborts.
- If AI classification of a particular class fails, the scan continues and MethodAtlas emits base metadata without AI suggestions for that class.
