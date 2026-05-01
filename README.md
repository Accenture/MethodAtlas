# MethodAtlas

<img src="MethodAtlas.png" width="20%" align="right" alt="MethodAtlas logo" />

MethodAtlas is a CLI tool that scans test source trees for test methods and emits one structured record per discovered method — with optional AI-assisted security classification. Java (JUnit 5, JUnit 4, TestNG), C# (.NET — xUnit, NUnit, MSTest), and TypeScript/JavaScript (Jest, Vitest, Mocha) are supported out of the box; additional languages can be added as plugins.

It is built for teams that must demonstrate test coverage of security properties to auditors, regulators, or security review boards: it separates **deterministic source analysis** from **optional AI interpretation** so that every result is traceable, repeatable, and defensible.

## Why MethodAtlas

Security-focused teams in regulated industries need more than a passing test suite. They need to demonstrate *which* tests cover *which* security controls, at a level of detail that satisfies external review.

MethodAtlas addresses this by turning an existing test suite into a structured inventory with minimal setup. See [Supported languages and frameworks](#supported-languages-and-frameworks) for the full list. The plugin architecture allows adding further languages without modifying the core tool.

| Challenge | What MethodAtlas provides |
| --- | --- |
| "Show us your security test coverage" | AI-classified inventory with rationale per method |
| "Prove the tests haven't changed since last audit" | Per-class SHA-256 content fingerprints (`-content-hash`) |
| "Integrate this into our SAST pipeline" | Native **SARIF 2.1.0** output, compatible with GitHub Advanced Security, VS Code, Azure DevOps, and SonarQube |
| "We can't send source code to external AI APIs" | Local inference via **Ollama**, or a two-phase **manual AI workflow** for air-gapped environments |
| "Classification must be consistent and auditable" | Closed, versioned **security taxonomy** with optional custom taxonomy aligned to your controls framework |
| "We need confidence scores, not just yes/no" | Per-method AI **confidence scores** (`0.0–1.0`) for threshold-based filtering and human-review queues |
| "Annotate the source files for us" | **Apply-tags mode** writes `@DisplayName` and `@Tag` annotations directly into source files |
| "Our @Tag annotations look stale" | **Tag vs AI drift detection** flags disagreements between source annotations and AI classification |

## Key capabilities

- **Deterministic test discovery** — AST-based analysis (JavaParser for Java, ANTLR4 grammar for C#); no inference, no false positives on method existence; framework detected automatically from imports/using directives
- **Multi-language plugin architecture** — Java, C#, and TypeScript/JavaScript plugins ship in separate JARs loaded via `ServiceLoader`; new languages require no changes to the core tool
- **SARIF 2.1.0 output** — first-class integration with static analysis platforms and IDE tooling
- **AI security classification** — classifies each test method against a closed security taxonomy; supports Ollama, OpenAI, Anthropic, Azure OpenAI, Groq, xAI, GitHub Models, Mistral, and OpenRouter
- **Confidence scoring** — per-method decimal score (`-ai-confidence`); filter by threshold for audit packages
- **Content hash fingerprints** — SHA-256 of the class AST text (`-content-hash`); all methods in the same class share the same hash; enables incremental scanning and change detection
- **AI result cache** — reuse previous AI classifications by hash (`-ai-cache`); unchanged classes cost zero API calls
- **Tag vs AI drift detection** — `-drift-detect` flags methods where `@Tag("security")` in source disagrees with the AI classification
- **Multi-root and monorepo scanning** — `-emit-source-root` appends a `source_root` column to CSV/plain output, disambiguating records when the same FQCN appears under different modules
- **Classification overrides** — `-override-file` records human-reviewed corrections; overrides persist across re-runs and set confidence to `1.0` or `0.0`
- **Delta report** — `-diff` compares two CSV scans and emits a change report: methods added, removed, or modified between runs; useful for CI regression gates
- **Security-only filter** — `-security-only` suppresses non-security methods from CSV/plain output; applied automatically in SARIF mode
- **Mismatch limit** — `-mismatch-limit` safety gate for `-apply-tags-from-csv`; aborts without touching source files when the CSV diverges from the current codebase
- **GitHub Actions annotations** — `-github-annotations` emits inline PR annotations for security-relevant methods without requiring a GitHub Advanced Security licence
- **Apply-tags** — writes AI-suggested `@DisplayName` and `@Tag` annotations back into source files; idempotent
- **Apply-tags-from-csv** — applies human-reviewed annotation decisions from a CSV back to source; separates the review step from the write-back
- **Manual AI workflow** — two-phase prepare/consume workflow for environments where API access is blocked
- **Local inference** — Ollama support keeps source code entirely within your network
- **YAML configuration** — share scan settings across a team or CI pipeline without repeating CLI flags
- **Custom taxonomy** — supply an external taxonomy file aligned to ISO 27001, NIST SP 800-53, PCI DSS, or your own controls framework
- **Scan provenance** — `-emit-metadata` prepends tool version and timestamp to CSV; embed in evidence packages
- **Multiple output modes** — CSV (default), plain text, SARIF, and GitHub Actions annotations

## Quick start

Build and unpack the distribution archive, then:

```bash
cd methodatlas-<version>/bin

# Static scan — outputs fqcn, method, loc, tags
./methodatlas /path/to/project

# AI security classification (local Ollama)
./methodatlas -ai /path/to/project

# SARIF output — pipe to a file for upload to GitHub Advanced Security
./methodatlas -sarif /path/to/project > results.sarif

# SARIF + AI enrichment + content hash fingerprints
./methodatlas -ai -sarif -content-hash /path/to/project > results.sarif

# Apply AI-suggested annotations back into source files
./methodatlas -ai -apply-tags /path/to/tests

# Apply reviewed CSV decisions back into source files
./methodatlas -apply-tags-from-csv reviewed.csv /path/to/tests

# GitHub Actions inline PR annotations
./methodatlas -ai -github-annotations /path/to/tests
```

See [docs/cli-reference.md](docs/cli-reference.md) for the complete option reference.

## Desktop GUI

The `methodatlas-gui` module provides a professional Swing desktop application
for interactive analysis and tag review. It is aimed at security engineers and
auditors who need to review, override, and apply AI-suggested `@Tag` annotations
without touching the command line.

### Features

- **Directory picker** — browse to any test source root; the last-used path is remembered
- **Background analysis** — test discovery and AI enrichment run on a background thread; the UI remains responsive throughout
- **Results tree** — methods are grouped by class; colour-coded status indicators show at a glance which methods need attention (orange `⚠`), are already tagged correctly (green `✓`), or have no AI data (grey `○`)
- **Syntax-highlighted editor** — the source file for the selected method opens in an embedded RSyntaxTextArea editor with line numbers and code folding; supported languages: Java, C#, TypeScript, JavaScript
- **Tag editor** — shows the method's current `@Tag` values alongside AI-suggested tags as interactive toggle chips; individual tags can be accepted or rejected before writing back to the source file
- **Custom override** — enter comma-separated tags manually to complement or replace AI suggestions
- **Staged workflow** — "Apply to Source" stages changes in memory without writing to disk; a **Save All Changes** toolbar button batches all staged patches per file into a single write, eliminating line-number drift when multiple methods in the same class are modified; staged methods are shown with an orange pencil `✎` icon in the tree; the application asks to save on exit if staged changes are present
- **Audit trail** — every **Save All Changes** operation writes two artefacts into a hidden `.methodatlas/` directory inside the scanned project root:
  - a timestamped **evidence CSV** (`methodatlas-YYYYMMDD-HHmmss.csv`) using the same column schema as the CLI `DeltaReport` CSV, recording the AI suggestion and the user's final decision for each patched method — never overwritten, accumulates per save operation
  - a cumulative **override YAML** (`overrides.yaml`) in the `ClassificationOverride` format consumed by the CLI `--override` flag, enabling future analysis runs to reproduce the same decisions without re-invoking AI; entries include an ISO-8601 timestamp and, when configured, the reviewer's identity in the `note` field
- **Activity panel** — collapsible panel above the status bar that appears whenever analysis is running; shows the class currently being sent to AI, deterministic progress counter (`X / Y classes`), elapsed time, and a scrollable log of completed classes with per-class timing and method counts; makes it immediately visible if a long AI request has stalled
- **AI profiles** — multiple named provider configurations (e.g. "Fast Ollama", "GPT-4o", "Java-only") coexist side by side; the active profile is selected from a toolbar combo box without opening Settings; switching profiles takes effect on the next run
- **Plugin selection** — the Settings dialog lists all discovery plugins detected on the classpath; unchecking a plugin excludes it from the next scan, useful when only one language needs to be processed
- **Settings dialog** — profile manager (New / Delete / Rename + full form per profile) for any of the ten supported AI providers (Ollama, OpenAI, Anthropic, Azure OpenAI, Groq, xAI, GitHub Models, Mistral, OpenRouter, or AUTO) with API key, model name, base URL, timeout, and retry settings; **Operator name** field (Audit section) whose value appears in every audit record; theme selector (IntelliJ Light, Flat Dark, Flat Light, Darcula); **Reset to Defaults** button restores all fields to built-in values; **Open folder** button opens the directory containing the settings file in the system file manager

### Build and run

```bash
# Build the GUI distribution
./gradlew :methodatlas-gui:build

# Run directly from Gradle
./gradlew :methodatlas-gui:run

# Or run the generated start script
methodatlas-gui/build/install/MethodAtlasGUI/bin/MethodAtlasGUI
```

Settings are persisted to `%APPDATA%\MethodAtlasGUI\settings.json` on Windows and
`$XDG_CONFIG_HOME/methodatlas-gui/settings.json` (or `~/.methodatlas-gui/settings.json`)
on Linux and macOS.

## Supported languages and frameworks

| Language | Plugin module | Test frameworks | Tag attribute | Display-name support | Requires |
| --- | --- | --- | --- | --- | --- |
| Java | `methodatlas-discovery-jvm` | JUnit 5, JUnit 4, TestNG (auto-detected from imports) | `@Tag("value")` | `@DisplayName("text")` | — |
| C# (.NET) | `methodatlas-discovery-dotnet` | xUnit, NUnit, MSTest (auto-detected from `using` directives) | `[Category]` / `[Trait]` / `[TestCategory]` | xUnit `DisplayName=` only | — |
| TypeScript / JavaScript | `methodatlas-discovery-typescript` | Jest, Vitest, Mocha (identified by function call names) | — | — | Node.js 18+ on PATH |

All three plugins ship with the default distribution. The TypeScript plugin
disables itself gracefully when Node.js is not on the PATH. Additional languages
can be added by implementing `TestDiscovery` (in `methodatlas-api`), declaring a
unique `pluginId()`, and dropping the JAR on the classpath — no changes to the
core tool are required.

## What MethodAtlas reports

For each discovered test method, MethodAtlas emits one record.

**Source-derived fields:**

| Field | Present when | Description |
| --- | --- | --- |
| `fqcn` | Always | Fully qualified class name (Java/C#); dot-separated relative file path without extension (TypeScript) |
| `method` | Always | Test method name; for TypeScript includes describe-block hierarchy (e.g. `AuthService > should authenticate`) |
| `loc` | Always | Inclusive line count of the method declaration |
| `tags` | Always | Existing tag-annotation values (`@Tag` for Java; `[Category]`/`[Trait]`/`[TestCategory]` for C#) |
| `source_root` | `-emit-source-root` | CWD-relative path of the scan root that produced the record; disambiguates records in multi-root or monorepo projects |
| `content_hash` | `-content-hash` | SHA-256 fingerprint of the enclosing class |

**AI enrichment fields** (present when `-ai` is enabled):

| Field | Present when | Description |
| --- | --- | --- |
| `ai_security_relevant` | `-ai` | Whether the model classified the test as security-relevant |
| `ai_display_name` | `-ai` | Suggested security-oriented `@DisplayName` value |
| `ai_tags` | `-ai` | Suggested security taxonomy tags (e.g. `security;auth`, `security;crypto`) |
| `ai_reason` | `-ai` | Short rationale for the classification |
| `ai_interaction_score` | `-ai` | Fraction of assertions that only verify method calls rather than outcomes (`0.0` = all outcome checks, `1.0` = all interaction checks) |
| `ai_confidence` | `-ai` + `-ai-confidence` | Model confidence score `0.0–1.0` |
| `tag_ai_drift` | `-ai` + `-drift-detect` | Disagreement between source `@Tag("security")` and AI classification |

## Output modes

### CSV (default)

```csv
fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score
com.acme.auth.LoginTest,testLoginWithValidCredentials,12,,,true,SECURITY: auth - validates session token,security;auth,Verifies session token is issued on successful login.,0.0
com.acme.util.DateTest,format_returnsIso8601,5,,,false,,,,0.1
```

### SARIF 2.1.0

```bash
./methodatlas -ai -sarif /path/to/tests > results.sarif
```

Produces a single valid [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html) JSON document. Security-relevant methods receive SARIF level `note`; all other test methods receive level `none`. Rule IDs are derived from AI taxonomy tags (`security/auth`, `security/crypto`, etc.).

SARIF is natively consumed by:
- **GitHub Advanced Security** — upload via the `upload-sarif` action to surface findings in the Security tab
- **VS Code** — [SARIF Viewer extension](https://marketplace.visualstudio.com/items?itemName=MS-SarifVSCode.sarif-viewer) renders results inline
- **Azure DevOps** — SARIF viewer pipeline extension
- **SonarQube** — import via the generic issue import format after conversion

### Plain text

```bash
./methodatlas -plain /path/to/project
```

Human-readable line-oriented output, useful for terminal inspection and shell scripting.

### GitHub Actions annotations

```bash
./methodatlas -ai -github-annotations /path/to/tests
```

Emits `::notice` / `::warning` workflow commands that GitHub Actions renders as inline annotations on the PR diff. Does not require a GitHub Advanced Security licence.

See [docs/output-formats.md](docs/output-formats.md) for full format descriptions and examples.

## Audit trail (GUI)

Every **Save All Changes** operation in the desktop GUI writes two artefacts into a hidden `.methodatlas/` directory inside the scanned project root.

### Evidence CSV

A timestamped, immutable file named `methodatlas-YYYYMMDD-HHmmss.csv` is created on each save.  Each row records one patched method and uses the same column schema as the CLI `DeltaReport` CSV:

| Column | Description |
| --- | --- |
| `fqcn` | Fully-qualified class name |
| `method` | Simple method name |
| `loc` | Lines of code |
| `tags` | Semicolon-separated tags written to source |
| `display_name` | `@DisplayName` text written to source (empty = unchanged) |
| `ai_security_relevant` | AI classification (`true`/`false`) |
| `ai_display_name` | AI-suggested display name |
| `ai_tags` | Semicolon-separated AI-suggested tags |
| `ai_reason` | AI rationale |
| `ai_confidence` | AI confidence score (0.0–1.0) |
| `ai_interaction_score` | AI interaction score |
| `tag_ai_drift` | `none` / `tag-only` / `ai-only` — divergence between applied and AI tags |

The file is never overwritten.  Files accumulate over time, giving an ordered history of each review session.

### Override YAML

`overrides.yaml` is updated (or created) on every save.  It uses the `ClassificationOverride` format accepted by the CLI `--override` flag:

```yaml
overrides:
  - fqcn: com.acme.crypto.AesGcmTest
    method: roundTrip_encryptDecrypt
    securityRelevant: true
    tags: [security, crypto]
    displayName: "SECURITY: crypto — AES-GCM round-trip"
    reason: "Verifies ciphertext integrity under AES-GCM — critical crypto test"
    note: "Reviewed 2026-05-01T14:30:00 by Jane Smith"
```

The `note` field is populated automatically with the ISO-8601 review timestamp.  When the **Operator name** field is set in Settings → Audit, it is appended as `by <name>`, providing a clear reviewer identity for regulated environments.  Existing entries are updated in place; new entries are appended.

Passing `--override .methodatlas/overrides.yaml` to a subsequent CLI run reproduces the same tag decisions without re-invoking the AI, which is essential for reproducible CI pipelines and regulated release processes.

## SARIF for regulated environments

SARIF (OASIS Static Analysis Results Interchange Format) gives each finding a physical location, a logical location (fully qualified method name), and a structured properties bag that includes all AI enrichment fields and, when `-content-hash` is used, a SHA-256 fingerprint traceable to an exact class revision. This makes MethodAtlas output independently auditable without custom tooling.

For the full result schema, rule IDs, and platform-specific integration notes, see [docs/output-formats.md](docs/output-formats.md#sarif-mode). For regulated-environment deployment guidance (PCI-DSS, ISO 27001, air-gapped), see [docs/deployment/](docs/deployment/index.md).

## AI security classification

When `-ai` is enabled, MethodAtlas submits each parsed test class to a configured AI provider for security classification. The model receives:

1. The **closed security taxonomy** — a controlled set of tags that constrains what the model can return
2. The **exact list of test methods** discovered by the parser — the model cannot invent or skip methods
3. The **full source text** as context for semantic interpretation

Because discovery is AST-based and AI classification is constrained by a fixed tag set, the structural inventory is deterministic even when the semantic interpretation uses a language model.

### Supported providers

| Provider value | AI product / platform | Deployment | Free tier |
| --- | --- | --- | --- |
| `ollama` | Any locally installed model | Local — source never leaves the machine | — |
| `auto` | Ollama → API key fallback | Local first, cloud fallback | — |
| `openai` | ChatGPT / OpenAI API | Cloud | No |
| `anthropic` | Claude / Anthropic API | Cloud | No |
| `xai` | Grok / xAI API | Cloud | Limited |
| `groq` | Groq (fast LPU inference) | Cloud | Yes |
| `github_models` | GitHub Models | Cloud | Yes (GitHub account) |
| `mistral` | Mistral AI | Cloud (EU) | Limited |
| `openrouter` | Many models via OpenRouter | Cloud | Yes (free models) |
| `azure_openai` | Azure OpenAI Service | Customer's Azure tenant | No |

See [docs/ai/providers.md](docs/ai/providers.md) for per-provider setup instructions, including which well-known AI assistant corresponds to which provider value.

### Confidence scoring

Pass `-ai-confidence` to add a `0.0–1.0` confidence score per method. The score appears in the `ai_confidence` column, whose position shifts when optional columns such as `-content-hash` or `-emit-source-root` are enabled. Use the header row to locate it:

```bash
./methodatlas -ai -ai-confidence /path/to/tests > scan.csv

# Keep header + rows where ai_confidence >= 0.7
# Locate the column index from the header, then filter by name:
python3 -c "
import csv, sys
r = csv.DictReader(open('scan.csv'))
w = csv.DictWriter(sys.stdout, fieldnames=r.fieldnames)
w.writeheader()
for row in r:
    if float(row.get('ai_confidence') or 0) >= 0.7:
        w.writerow(row)
"
```

| Score | Meaning |
| --- | --- |
| `1.0` | Explicitly and unambiguously tests a named security property |
| `~0.7` | Clearly tests a security-adjacent concern |
| `~0.5` | Plausible but ambiguous; candidate for manual review |
| `0.0` | Not security-relevant |

See [docs/ai/confidence.md](docs/ai/confidence.md) for the full interpretation guide.

## Content hash fingerprints and incremental scanning

Pass `-content-hash` to append a SHA-256 fingerprint of each class to every emitted record:

```bash
./methodatlas -content-hash -sarif /path/to/tests > results.sarif
```

The hash is computed from the parsed AST text of the enclosing class (JavaParser for Java; ANTLR4 parse tree for C#; source text for TypeScript). All methods in the same class share the same value, and the hash changes only when the class body changes — not when unrelated files are modified.

Practical applications:
- **Incremental scanning** — skip classes whose hash has not changed since the last run
- **Audit traceability** — correlate a SARIF finding back to the exact class revision that produced it
- **CI change detection** — detect modified test classes between two pipeline stages without diffing source files

### AI result cache

Pass `-ai-cache <prev-scan.csv>` to reuse AI classifications from a previous run. Before
calling the AI provider for a class, MethodAtlas checks whether that class's content hash
appears in the cache file. On a hit, the stored result is used directly — no API call is
made. Only changed or new classes incur a provider call.

```bash
# Day 1 — full scan; save the result as the cache
./methodatlas -ai -content-hash src/test/java > scan.csv

# Day 2, 3, … — unchanged classes cost nothing
./methodatlas -ai -content-hash -ai-cache scan.csv src/test/java > scan-new.csv
```

When producing SARIF output, use a two-pass approach: the first pass refreshes the CSV
cache (calling AI only for changed classes), the second pass generates SARIF from the
cache with zero AI calls.

```bash
# Pass 1: refresh cache (AI called only for changed classes)
./methodatlas -ai -content-hash -ai-cache scan.csv src/test/java > scan-new.csv

# Pass 2: generate SARIF from cache — zero AI calls
./methodatlas -ai -content-hash -ai-cache scan-new.csv -sarif src/test/java > results.sarif
```

See [docs/ai/caching.md](docs/ai/caching.md) for the full cache documentation and
[docs/ci/github-actions.md](docs/ci/github-actions.md) for the complete GitHub Actions
workflow that implements this pattern.

## Manual AI workflow

For environments where direct AI API access is blocked by corporate policy, MethodAtlas supports a two-phase manual workflow:

```bash
# Phase 1 — write prompts to files
./methodatlas -manual-prepare ./work ./responses /path/to/tests

# (paste each work file's AI prompt into a chat window, save the response)

# Phase 2 — consume responses and emit the enriched CSV (or apply tags)
./methodatlas -manual-consume ./work ./responses /path/to/tests
./methodatlas -manual-consume ./work ./responses -apply-tags /path/to/tests
```

All taxonomy and confidence flags apply equally in manual mode. The consume phase is incremental — you can process classes as responses arrive rather than waiting for the full batch.

See [docs/usage-modes/manual.md](docs/usage-modes/manual.md) for the complete workflow.

## YAML configuration

Store shared settings in a YAML file so that CI pipelines and team members use consistent options without repeating flags:

```yaml
outputMode: sarif
contentHash: true
ai:
  enabled: true
  provider: ollama
  model: qwen2.5-coder:7b
  confidence: true
  taxonomyMode: optimized
```

```bash
./methodatlas -config ./methodatlas.yaml /path/to/tests
```

Command-line flags always override YAML values. See [docs/cli-reference.md](docs/cli-reference.md#-config-file) for the complete field reference.

## Distribution layout

```text
methodatlas-<version>/
├── bin/
│   ├── methodatlas
│   └── methodatlas.bat
└── lib/
    ├── methodatlas-<version>.jar
    └── *.jar  (runtime dependency libraries)
```

The startup scripts in `bin/` configure the classpath automatically to include all JARs in `lib/`, so no manual setup is required after extraction.

## Documentation

Full documentation is available at [accenture.github.io/MethodAtlas](https://accenture.github.io/MethodAtlas/).

| Document | Contents |
| --- | --- |
| [docs/cli-reference.md](docs/cli-reference.md) | Complete option reference, YAML schema, exit codes, and example commands |
| [docs/cli-examples.md](docs/cli-examples.md) | Practical command-line examples grouped by use case |
| [docs/output-formats.md](docs/output-formats.md) | CSV, plain text, SARIF, and GitHub Annotations format descriptions |
| [docs/migration.md](docs/migration.md) | Breaking-change notes and upgrade steps for each major version boundary |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Diagnosis and remedies for common problems |
| [docs/discovery-plugins.md](docs/discovery-plugins.md) | Per-language plugin configuration: Java, C#, TypeScript/JavaScript |
| [docs/usage-modes/](docs/usage-modes/index.md) | All operating modes: static inventory, API AI, manual workflow, apply-tags, apply-tags-from-csv, delta, security-only |
| [docs/ai/providers.md](docs/ai/providers.md) | Per-provider setup: Ollama, OpenAI, Anthropic, Azure OpenAI, Groq, xAI, GitHub Models, Mistral, OpenRouter |
| [docs/ai/overrides.md](docs/ai/overrides.md) | Classification override file: format, governance, and CI integration |
| [docs/ai/confidence.md](docs/ai/confidence.md) | Confidence scoring: interpretation and threshold guidance |
| [docs/ai/caching.md](docs/ai/caching.md) | AI result caching: skip unchanged classes, two-pass SARIF pattern, CI cache key strategy |
| [docs/ai/drift-detection.md](docs/ai/drift-detection.md) | Tag vs AI drift detection: detecting stale `@Tag("security")` annotations |
| [docs/ai/interaction-score.md](docs/ai/interaction-score.md) | Placebo-test detection: interaction-score semantics and CI thresholds |
| [docs/compliance.md](docs/compliance.md) | Compliance framework mapping: OWASP SAMM, NIST SSDF, ISO 27001, DORA; reproducibility statement |
| [docs/deployment/](docs/deployment/index.md) | Regulated environment guidance: PCI-DSS, ISO 27001, NIST SSDF, DORA, SOC 2, air-gapped |
| [docs/deployment/onboarding.md](docs/deployment/onboarding.md) | Onboarding a brownfield codebase: six-phase progression from static scan to CI gate |
| [docs/concepts/data-governance.md](docs/concepts/data-governance.md) | What data is submitted to AI providers, data residency options, enterprise secret management |
| [docs/concepts/for-security-teams.md](docs/concepts/for-security-teams.md) | MethodAtlas from a security-team perspective: evidence packages, audit trails |
| [docs/concepts/asvs-mapping.md](docs/concepts/asvs-mapping.md) | Mapping MethodAtlas taxonomy tags to OWASP ASVS requirements |
| [docs/concepts/vs-sast.md](docs/concepts/vs-sast.md) | How MethodAtlas differs from and complements traditional SAST tooling |
| [docs/concepts/remediation.md](docs/concepts/remediation.md) | Guidance on acting on MethodAtlas findings: fixing placebo tests, adding assertions |
| [docs/publication-order.txt](docs/publication-order.txt) | pandoc publication order — all 50 documents in reading sequence; used by `methodatlas-docs:generatePdf` |
