# MethodAtlas

<img src="MethodAtlas.png" width="20%" align="right" alt="MethodAtlas logo" />

MethodAtlas is a CLI tool that scans Java source trees for JUnit test methods and emits one structured record per discovered method.

The tool combines **deterministic source analysis** with optional **AI-assisted security classification** so that developers and auditors can quickly inventory a test suite and identify which tests validate security properties.

Unlike approaches that rely entirely on LLM inference, MethodAtlas separates the problem into two independent steps:

- **Deterministic discovery** — a Java AST parser determines exactly which test methods exist, with no hallucination possible
- **AI interpretation** — an optional model classifies those methods, suggests security taxonomy tags, and can score how confident it is

The parser determines *what exists* in the code.  
The AI suggests *what it means*.

## What MethodAtlas reports

For each discovered JUnit test method, MethodAtlas emits a single record.

**Source-derived fields** (always present):

| Field | Description |
| --- | --- |
| `fqcn` | Fully qualified class name |
| `method` | Test method name |
| `loc` | Inclusive line count of the method declaration |
| `tags` | Existing JUnit `@Tag` values declared on the method |

**AI enrichment fields** (present when `-ai` is enabled):

| Field | Description |
| --- | --- |
| `ai_security_relevant` | Whether the model classified the test as security-relevant |
| `ai_display_name` | Suggested security-oriented `@DisplayName` |
| `ai_tags` | Suggested security taxonomy tags |
| `ai_reason` | Short rationale for the classification |
| `ai_confidence` | Model confidence score `0.0–1.0` (only when `-ai-confidence` is also passed) |

## Quick start

Build and unpack the distribution archive, then:

```bash
cd methodatlas-<version>/bin

# Static scan only
./methodatlas /path/to/project

# With AI enrichment (local Ollama)
./methodatlas -ai /path/to/project

# With AI enrichment and confidence scoring
./methodatlas -ai -ai-confidence /path/to/project
```

See [docs/cli-reference.md](docs/cli-reference.md) for all options and examples.

## Distribution layout

```text
methodatlas-<version>/
├── bin/
│   ├── methodatlas
│   └── methodatlas.bat
└── lib/
    └── methodatlas-<version>.jar
```

## Why this is useful

MethodAtlas is particularly valuable when you need to:

- **Inventory a large JUnit suite** — get one structured row per test without reading every class by hand
- **Prepare for a security audit** — identify which tests already validate security properties and where gaps exist
- **Enforce consistent tagging** — surface tests that lack `@Tag` or `@DisplayName` annotations
- **Export for reporting** — the CSV output feeds directly into spreadsheets, dashboards, or CI quality gates
- **Measure coverage over time** — run after each sprint to track whether the security test count is growing

Because the output is plain CSV or plain text, it integrates into any pipeline without custom tooling.

## Documentation

| Document | Contents |
| --- | --- |
| [docs/output-formats.md](docs/output-formats.md) | CSV and plain-text output formats, column descriptions |
| [docs/ai-guide.md](docs/ai-guide.md) | AI providers, confidence scoring, taxonomy, manual workflow |
| [docs/cli-reference.md](docs/cli-reference.md) | Complete option reference and example commands |
