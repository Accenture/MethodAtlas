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
| `-sarif` | Emit SARIF 2.1.0 JSON instead of CSV; security-only filtering is applied automatically (see below) | CSV mode |
| `-github-annotations` | Emit GitHub Actions `::notice`/`::warning` workflow commands for security-relevant methods; does not require a GitHub Advanced Security licence | CSV mode |
| `-emit-metadata` | Prepend `# key: value` comment lines before the CSV header | Off |
| `-file-suffix <suffix>` | Include files whose name ends with `suffix`; may be repeated; first occurrence replaces the default | `Test.java` |
| `-test-marker <name>` | Treat methods carrying marker `name` as test methods; language-neutral: annotation simple names for Java/Kotlin, attribute names for C#; may be repeated; first occurrence replaces the default set; `-test-annotation` is accepted as a backward-compatible alias | `Test`, `ParameterizedTest`, `RepeatedTest`, `TestFactory`, `TestTemplate` |
| `-property <key>=<value>` | Set a plugin-specific property; may be repeated; same key accumulates into a list; forwarded verbatim to each discovery plugin | — |
| `-content-hash` | Append a SHA-256 fingerprint of each class source to every emitted record | Off |
| `-apply-tags` | Write AI-generated `@DisplayName` and `@Tag` annotations back to the scanned source files; requires AI to be enabled | Off |
| `-apply-tags-from-csv <file>` | Apply reviewed annotation decisions from a MethodAtlas CSV back to the scanned source files; the CSV is the complete desired state for every test method's `@Tag` set and `@DisplayName` | Off |
| `-mismatch-limit <n>` | Used with `-apply-tags-from-csv`: abort without making any changes if the number of mismatches between the CSV and the current source tree reaches or exceeds `n`; `-1` means warn and proceed | `-1` |
| `-security-only` | Suppress non-security methods from CSV and plain-text output; only methods with `ai_security_relevant=true` are emitted; requires `-ai` or `-override-file` to have any effect; in SARIF mode this filter is already applied by default | Off |
| `-include-non-security` | Opt-in to include all test methods in SARIF output, disabling the automatic security-only filter; has no effect in CSV or plain-text modes | Off |
| `-drift-detect` | Append a `tag_ai_drift` column to CSV/plain output comparing the source-level `@Tag("security")` annotation against the AI security-relevance classification; values are `none`, `tag-only`, or `ai-only`; SARIF and GitHub Annotations always include drift when AI is enabled | Off |
| `-emit-source-root` | Append a `source_root` column to CSV output (and a `SRCROOT=` token to plain-text output) identifying which scan root each record originated from; essential when the same FQCN can appear under multiple source trees | Off |
| `-override-file <file>` | Load a YAML classification override file; human corrections are applied after AI classification on every run | — |
| `-diff <before.csv> <after.csv>` | Compare two MethodAtlas scan outputs and emit a delta report; all other flags are ignored | — |
| `[path ...]` | One or more root paths to scan | Current directory |

## AI options

| Argument | Meaning | Default |
| --- | --- | --- |
| `-ai` | Enable AI enrichment | Off |
| `-ai-confidence` | Ask the model to include a confidence score (`0.0–1.0`) per classification | Off |
| `-ai-provider <provider>` | Select provider: `auto`, `ollama`, `openai`, `openrouter`, `anthropic`, `azure_openai`, `groq`, `xai`, `github_models`, `mistral` | `auto` |
| `-ai-model <model>` | Provider-specific model identifier | `qwen2.5-coder:7b` |
| `-ai-base-url <url>` | Override provider base URL | Provider default |
| `-ai-api-key <key>` | Supply API key directly on the command line | — |
| `-ai-api-key-env <name>` | Read API key from an environment variable | — |
| `-ai-taxonomy <path>` | Load taxonomy text from an external file | Built-in taxonomy |
| `-ai-taxonomy-mode <mode>` | Select built-in taxonomy variant: `default` or `optimized` | `default` |
| `-ai-cache <file>` | Use a previous scan's CSV as a classification cache; classes whose SHA-256 hash matches are reused without calling the AI provider | — |
| `-ai-max-class-chars <count>` | Skip AI for classes larger than this character count | `40000` |
| `-ai-timeout-sec <seconds>` | Request timeout for provider calls | `90` |
| `-ai-max-retries <count>` | Retry limit for AI operations | `1` |
| `-ai-api-version <version>` | Azure OpenAI REST API version appended as `?api-version=` query parameter; only used when provider is `azure_openai` | `2024-02-01` |

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
overrideFile: .methodatlas-overrides.yaml   # optional
fileSuffixes:
  - Test.java
  - IT.java
testMarkers:             # annotation/attribute names for Java/.NET; empty = auto-detect
  - Test
  - ParameterizedTest
properties:              # plugin-specific key/multi-value pairs (optional)
  functionNames:         # example: test function names for a Jest/Mocha/Vitest plugin
    - test
    - it
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
  apiVersion: 2024-02-01   # Azure OpenAI REST API version (azure_openai only)
driftDetect: false       # append tag_ai_drift column to CSV/plain output  (default: false)
includeNonSecurity: false  # include non-security methods in SARIF output  (default: false)
```

All fields are optional. Unknown fields are silently ignored. This makes it safe to add future fields to a shared configuration file without breaking older versions.

A configuration file is useful in CI pipelines or when several team members share the same scan settings. Individual developers can override specific values on the command line without editing the shared file.

### `-plain`

Switches output from CSV to a human-readable line-oriented format. Method discovery and AI classification are unaffected.

### `-sarif`

Switches output to a single [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html) JSON document. MethodAtlas buffers all discovered test methods and serializes the complete document after the scan finishes, so the JSON is valid even when the scan spans many files.

Security-relevant methods receive SARIF level `note` and a `security-severity` property derived from the AI taxonomy tag (used by GitHub Code Scanning to display Critical / High / Medium / Low). Rule objects carry a `properties.tags` array that populates the tag filter in the GitHub Code Scanning UI.

**Security-only by default:** selecting SARIF mode automatically applies the security-only filter. Only methods classified as security-relevant are emitted; ordinary test methods are excluded. This default exists because SARIF is consumed by GitHub Code Scanning and equivalent security tooling that expects actionable findings, not an inventory of every test method. To include all methods, pass [`-include-non-security`](#-include-non-security).

See [output-formats.md](output-formats.md#sarif-mode) for the full schema description and an example document.

### `-github-annotations`

Switches output to GitHub Actions [workflow commands](https://docs.github.com/en/actions/writing-workflows/choosing-what-your-workflow-does/workflow-commands-for-github-actions) that GitHub intercepts and renders as inline annotations on the PR diff.

Only security-relevant methods produce output — non-security methods are silently skipped:

- `::warning` — when `ai_interaction_score >= 0.8`: the test only verifies that methods were called, not what they returned (potential placebo test).
- `::notice` — otherwise: a well-formed security test worth reviewing.

```bash
./methodatlas -ai -github-annotations src/test/java
```

**No GitHub Advanced Security licence is required.** The `::notice`/`::warning` commands are standard GitHub Actions features available on all plan tiers, in contrast to SARIF upload via the `upload-sarif` action which requires GitHub Advanced Security (an additional paid add-on for private repositories).

File paths in the annotations are derived from the scan root and the class FQCN, producing paths such as `src/test/java/com/acme/AuthTest.java` that GitHub resolves to the correct inline position in the PR diff for standard Maven/Gradle source layouts.

See [docs/ci/github-actions.md](ci/github-actions.md) for a complete GitHub Actions workflow example.

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

### `-test-marker <name>`

Extends or replaces the set of identifiers that MethodAtlas uses to recognise test methods. The flag is language-neutral:

| Language / framework | What a "marker" means |
|---|---|
| Java / Kotlin (JVM) | Annotation simple name — `Test`, `ParameterizedTest`, `Fact`, … |
| C# / .NET | Attribute simple name — `Test`, `TestMethod`, `Fact`, `Theory`, … |
| TypeScript / JavaScript | Leave empty; use `-property functionNames=test` instead |

`-test-annotation` is accepted as a backward-compatible alias and behaves identically.

#### Automatic framework detection (JVM)

When this flag is not set, MethodAtlas inspects the import declarations of each Java/Kotlin source file and selects the appropriate annotation set automatically:

| Detected framework | Imports matched | Annotation set used |
|---|---|---|
| JUnit 5 (Jupiter) | `org.junit.jupiter.*` | `Test`, `ParameterizedTest`, `RepeatedTest`, `TestFactory`, `TestTemplate` |
| JUnit 4 | `org.junit.*`, `junit.framework.*` | All JUnit 5 annotations plus `Theory` |
| TestNG | `org.testng.*` | `Test` |

Detection is per-file and accumulative: a file that imports both JUnit 4 and JUnit 5 (common during migrations) receives the union of both sets. Files with no recognisable test-framework imports fall back to the JUnit 5 default set.

#### Overriding the default set

The first occurrence of `-test-marker` replaces the entire default set and disables automatic framework detection; subsequent occurrences append to it:

```bash
# Recognise only @Test and @MyCustomTest (auto-detection disabled)
./methodatlas -test-marker Test -test-marker MyCustomTest /path/to/tests
```

Marker matching is performed against the simple name only (symbol resolution is not available in source-only parsing mode). False positives are possible if a project defines a custom annotation with the same simple name as a supported test annotation.

For a per-language cheat sheet — what "marker" means for Java, C#, TypeScript, and how to combine `-test-marker` with `-property` — see [Discovery Plugins](discovery-plugins.md).

### `-property <key>=<value>`

Sets a plugin-specific property and forwards it verbatim to each discovery plugin. The flag may be repeated; the same key accumulates into a list of values.

```bash
# Tell a Jest/Mocha/Vitest plugin which function names identify tests
./methodatlas -property functionNames=test -property functionNames=it /path/to/ts
```

Properties are ignored by plugins that do not recognise the key. In YAML:

```yaml
properties:
  functionNames:
    - test
    - it
```

### `-content-hash`

Appends a SHA-256 content fingerprint to every emitted record. The hash is computed from the JavaParser AST string representation of the enclosing class, so it is independent of file encoding, line endings, and unrelated file-level changes. When a class contains multiple test methods, all of them share the same hash value.

In CSV output, a `content_hash` column is appended immediately after `tags`:

```text
fqcn,method,loc,tags,display_name,content_hash
com.acme.tests.SampleOneTest,alpha,8,fast;crypto,,3a7f9b...
com.acme.tests.SampleOneTest,beta,6,param,,3a7f9b...
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

### `-security-only`

Suppresses non-security-relevant methods from CSV and plain-text output. Only
methods whose AI classification (or human override) has `securityRelevant=true`
are emitted; all others are silently dropped.

```bash
# Compact CSV — only security tests
./methodatlas -ai -security-only src/test/java
```

In **SARIF mode** this filter is applied automatically without this flag. Use
[`-include-non-security`](#-include-non-security) if you need the full inventory
in SARIF format.

The filter requires a source of security classifications. Without `-ai` or
`-override-file`, every method has `securityRelevant=false` and the output will
be empty. The flag can also be enabled via YAML configuration:

```yaml
securityOnly: true
```

See [Security-Only Filter](usage-modes/security-only.md) for SDLC integration
examples including CI count gates, audit CSV exports, and combining with the
delta report.

### `-include-non-security`

Opts in to including all test methods in **SARIF output**, disabling the
automatic security-only filter that SARIF mode applies by default. Has no effect
in CSV or plain-text modes (where all methods are already included by default).

```bash
# Full-inventory SARIF — all test methods included
./methodatlas -ai -sarif -include-non-security src/test/java > all-tests.sarif
```

The equivalent YAML setting:

```yaml
outputMode: sarif
includeNonSecurity: true
```

Use this option when you need a complete machine-readable test inventory in SARIF
format for a custom downstream pipeline. For GitHub Code Scanning, leave this
flag unset so that the Security tab shows only actionable security findings.

### `-drift-detect`

Appends a `tag_ai_drift` column to CSV and plain-text output comparing the source-level `@Tag("security")` annotation against the AI security-relevance classification for each method.

```bash
./methodatlas -ai -drift-detect src/test/java
```

The column appears at the end of each row, after `ai_confidence` when that flag is also set:

| Value | Meaning |
|---|---|
| `none` | Both sources agree |
| `tag-only` | `@Tag("security")` is present but AI disagrees — the annotation may be stale |
| `ai-only` | AI classifies as security-relevant but no `@Tag("security")` is present in source |

**SARIF and GitHub Annotations** include drift automatically when AI is enabled — no flag is needed. The column opt-in applies only to CSV/plain output to avoid breaking downstream scripts that don't expect the extra column.

See [Tag vs AI drift detection](ai/drift-detection.md) for use cases, regulated environment context, and a delta report integration example.

### `-emit-source-root`

Appends a `source_root` column to CSV output and a `SRCROOT=` token to plain-text output, identifying which scan root each record originated from.

```bash
./methodatlas -emit-source-root module-a/src/test/java module-b/src/test/java
```

Example CSV rows when scanning two modules:

```text
fqcn,method,loc,tags,display_name,source_root
com.acme.auth.AuthTest,testLogin,12,security,,module-a/src/test/java/
com.acme.auth.AuthTest,testLogout,8,,,module-b/src/test/java/
```

The `source_root` value is the CWD-relative path of the scan root for each record, with a trailing `/`. When a scan root is the current working directory itself, the value is `-` in plain-text output and an empty string in CSV.

**When to use this flag:** in projects where the same fully qualified class name appears under multiple source trees — for example in a monorepo where `module-a` and `module-b` both contain `com.acme.auth.AuthTest`. Without the column, rows from both modules are identical and cannot be attributed to either. With it, downstream tools (spreadsheets, scripts, CI dashboards) can filter or group by root.

This flag has no effect on SARIF or GitHub Annotations output. For those formats, the source root is already encoded in the full file path.

See [Multi-root and monorepo scanning](usage-modes/multi-root.md) for a detailed walkthrough.

### `-diff`

Compares two MethodAtlas CSV scan outputs and emits a delta report showing which
test methods were added, removed, or modified between the two runs. All other
flags are ignored when `-diff` is present.

```bash
./methodatlas -diff scan-before.csv scan-after.csv
```

Change-type indicators: `+` added, `-` removed, `~` modified (with a bracketed
summary of which fields changed). A summary line at the end shows counts and the
change in security-relevant method count.

The flag accepts only CSV files produced by MethodAtlas. For the richest output,
produce both input files with `-content-hash` (enables source-change detection)
and `-ai` (enables classification-change detection).

See [Delta Report](usage-modes/delta.md) for the full workflow, CI integration
examples, and regulatory context.

### `-override-file`

Loads a YAML classification override file before the scan begins. The file records human-reviewed corrections to AI classifications and is applied after AI classification (or in place of it in static mode) on every run.

```bash
./methodatlas -ai -override-file .methodatlas-overrides.yaml src/test/java
```

Overrides can correct false positives and false negatives, supply corrected taxonomy tags, add human-authored rationales, and attach reviewer notes that are never emitted in any output format. When an override is applied, confidence is set to `1.0` (security-relevant) or `0.0` (not security-relevant) to reflect the higher certainty of a human decision.

The flag can also be set via YAML configuration as a top-level `overrideFile` field.

See [Classification Overrides](ai/overrides.md) for the full file format, field reference, and CI integration examples.

### `-apply-tags`

Instead of emitting a report, modifies source files in place by inserting AI-generated `@DisplayName` and `@Tag` annotations on security-relevant test methods. Requires AI to be enabled via `-ai` or `-manual-consume`.

A summary line is always printed to standard output:

```text
Apply-tags complete: 12 annotation(s) added to 3 file(s)
```

See [Source Write-back](usage-modes/apply-tags.md) for the complete workflow and formatting guarantees.

### `-apply-tags-from-csv`

Instead of emitting a report, applies annotation decisions recorded in a reviewed MethodAtlas CSV back to the scanned source files. The CSV is treated as a **complete desired-state specification**: for each test method it defines the exact set of `@Tag` values and the `@DisplayName` text the method should have after the run.

```bash
# 1. Produce the CSV (optionally enriched with AI suggestions)
./methodatlas -ai src/test/java > review.csv

# 2. Review and adjust the tags and display_name columns in review.csv

# 3. Apply the decisions back to source
./methodatlas -apply-tags-from-csv review.csv src/test/java
```

What the engine does for each matched method:

- **`tags` column** — removes all existing `@Tag` and `@Tags` annotations, then adds exactly the tags listed (semicolon-separated).
- **`display_name` column** — three-way contract based on the cell value:
  - non-empty text → replaces any existing `@DisplayName`
  - empty string → removes `@DisplayName` if present
  - column absent from the CSV → `@DisplayName` left unchanged (backward compatibility with CSV files produced before this column was introduced)

Required imports (`org.junit.jupiter.api.Tag`, `org.junit.jupiter.api.DisplayName`) are added to files that need them.

A summary line is always printed:

```text
Apply-tags-from-csv complete: 7 change(s) in 2 file(s); 0 mismatch(es) skipped.
```

See [Apply Tags from CSV](usage-modes/apply-tags-from-csv.md) for the complete workflow, mismatch handling details, and CI integration.

### `-mismatch-limit <n>`

Used with `-apply-tags-from-csv`. Controls what happens when the CSV and the current source tree disagree (a method appears in one but not the other):

| Setting | Behaviour |
|---|---|
| `-1` (default) | Log mismatches as warnings; proceed with all matched methods |
| `0` | Abort immediately, even with zero mismatches (rarely useful) |
| `1` | Abort on the first mismatch — strictest; recommended for CI |
| `n ≥ 2` | Tolerate up to `n − 1` mismatches; abort when the count reaches `n` |

When the limit is reached or exceeded, MethodAtlas prints each mismatch and exits with code `1` without modifying any source file:

```text
MISMATCH (in CSV, not in source): com.example.LoginTest::deletedMethod
Apply-tags-from-csv aborted: 1 mismatch(es) >= limit 1. No source files were modified.
```

```bash
# Abort if there is even a single mismatch
./methodatlas -apply-tags-from-csv reviewed.csv -mismatch-limit 1 src/test/java
```

### `-ai`

Enables AI enrichment. Without this flag, MethodAtlas behaves as a pure static scanner. When present, a suggestion engine is initialized before scanning begins.

### `-ai-confidence`

Instructs the model to include a confidence score for each classification. The score appears as `ai_confidence` in CSV output (or `AI_CONFIDENCE=` in plain mode). Scores range from `0.0` (not security-relevant) to `1.0` (explicitly and unambiguously tests a named security property). See [Confidence scoring](ai/confidence.md) for the full interpretation table.

### AI interaction score

When AI enrichment is enabled, every record carries an `ai_interaction_score` regardless of other flags. The score (0.0–1.0) answers: *what fraction of this test's assertions only verify that methods were called, rather than what they returned or produced?*

| Value | Meaning |
| --- | --- |
| `1.0` | Every assertion is an interaction check — the test has no outcome assertion |
| `0.5` | Mixed: some outcome assertions alongside interaction-only checks |
| `0.0` | All assertions verify actual outputs or state |

A score of `1.0` on a security-relevant test is a strong signal of a **placebo test**: CI passes, coverage tools see the lines as covered, but no output or state is ever verified. Standard tooling (JaCoCo, PIT, PMD, SpotBugs) cannot detect this because they do not distinguish semantically between `verify(mock).call()` and `assertEquals(expected, actual)`. The AI can because it reads the test body and understands what each assertion checks. See [Interaction Score](ai/interaction-score.md) for a full explanation and CI usage examples.

In CSV output the column is named `ai_interaction_score` and appears immediately after `ai_reason` (before `ai_confidence` when that flag is enabled). In plain-text output it appears as `AI_INTERACTION_SCORE=`. In SARIF it is stored as `properties.aiInteractionScore`.

### `-ai-cache <file>`

Loads a previous MethodAtlas scan output as an AI result cache. Before calling the AI provider for a class, MethodAtlas computes the class's SHA-256 content fingerprint and checks whether that hash appears in `<file>`. If it does, the stored classification is reused directly — no API call is made. Classes with a changed or new hash are classified normally.

```bash
# Build the cache on first run
./methodatlas -ai -content-hash src/test/java > scan.csv

# Subsequent runs: unchanged classes are free
./methodatlas -ai -content-hash -ai-cache scan.csv src/test/java > scan-new.csv
```

The cache file must have been produced with `-content-hash -ai`; if either column is absent the cache degrades silently to a no-op. See [Result Caching](ai/caching.md) for a CI pipeline example.

### `-ai-provider <provider>`

Selects the provider implementation. Values are case-insensitive.

| Value | Behavior |
| --- | --- |
| `auto` | Probes local Ollama first; falls back to an API-key provider if Ollama is unreachable |
| `ollama` | Local Ollama inference at `http://localhost:11434` |
| `openai` | OpenAI API |
| `openrouter` | OpenRouter API |
| `anthropic` | Anthropic API |
| `azure_openai` | Azure OpenAI Service deployment (requires `baseUrl` and `model` = deployment name) |
| `groq` | Groq cloud inference (LPU hardware, OpenAI-compatible API) |
| `xai` | xAI API — Grok models |
| `github_models` | GitHub Models free inference (authenticate with `GITHUB_TOKEN`) |
| `mistral` | Mistral AI API |

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
| `groq` | `https://api.groq.com/openai` |
| `xai` | `https://api.x.ai/v1` |
| `github_models` | `https://models.inference.ai.azure.com` |
| `mistral` | `https://api.mistral.ai/v1` |
| `azure_openai` | *(required — no default; set to your resource endpoint)* |

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

Runs the prepare phase of the manual AI workflow. For each test class MethodAtlas writes a work file containing the AI prompt and creates an empty response placeholder. No CSV is emitted. See [Manual AI workflow](usage-modes/manual.md) for the full workflow.

### `-manual-consume <workdir> <responsedir>`

Runs the consume phase. MethodAtlas reads operator-filled response files and merges the AI JSON into the output CSV. Missing or empty response files are treated as absent AI data; the scan continues.

For practical examples grouped by use case, see [CLI Examples](cli-examples.md).

## Exit codes

| Code | Condition |
|---|---|
| `0` | Scan completed successfully; all source files processed |
| `1` | `-apply-tags-from-csv` aborted because the mismatch count reached or exceeded `-mismatch-limit` |
| `1` | A source file could not be read or written during `-apply-tags-from-csv` |
| `1` | A required argument value is missing or malformed (printed to stderr before exit) |

Note: AI classification failures for individual classes (provider timeout, parse error in the AI response) do not cause a non-zero exit. The affected rows are emitted with blank AI columns and the scan continues. Only structural errors — bad arguments, mismatch-limit violations, and I/O failures during source write-back — produce exit code `1`.
