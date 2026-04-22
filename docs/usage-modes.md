# Usage Modes

MethodAtlas supports three independent operating modes plus one modifier that can
be combined with any mode that involves AI output.

---

## Mode 1 — Static inventory

Scan test source files and emit structured metadata without contacting an AI provider.

**When to use:** CI pipelines that need a method inventory for diffing, SARIF generation
for code scanning dashboards, or initial project exploration.

```bash
# CSV to stdout (default)
./methodatlas src/test/java

# Plain text
./methodatlas -plain src/test/java

# SARIF 2.1.0 for GitHub Code Scanning
./methodatlas -sarif src/test/java > results.sarif

# Include integration test files alongside unit tests
./methodatlas -file-suffix Test.java -file-suffix IT.java src/test/java
```

**Output columns (CSV):** `fqcn`, `method`, `loc`, `tags`

No AI columns are emitted; AI-related fields remain blank.

---

## Mode 2 — API AI enrichment

Scan test sources and submit each class to an AI provider for security classification.
All supported providers share the same set of flags.

**When to use:** Development workflows with direct network access to an AI API,
automated nightly classification jobs, CI pipelines with a hosted AI gateway.

### Local inference (Ollama)

```bash
./methodatlas -ai \
  -ai-provider ollama \
  -ai-model qwen2.5-coder:7b \
  src/test/java
```

Ollama is probed automatically when `-ai-provider auto` (the default) is used and
Ollama is running locally.

### Hosted providers

```bash
# OpenRouter
export OPENROUTER_API_KEY=sk-...
./methodatlas -ai \
  -ai-provider openrouter \
  -ai-api-key-env OPENROUTER_API_KEY \
  -ai-model google/gemini-flash-1.5 \
  src/test/java

# OpenAI
export OPENAI_API_KEY=sk-...
./methodatlas -ai \
  -ai-provider openai \
  -ai-api-key-env OPENAI_API_KEY \
  -ai-model gpt-4o-mini \
  src/test/java

# Anthropic
export ANTHROPIC_API_KEY=sk-ant-...
./methodatlas -ai \
  -ai-provider anthropic \
  -ai-api-key-env ANTHROPIC_API_KEY \
  -ai-model claude-haiku-4-5-20251001 \
  src/test/java
```

### With confidence scoring

```bash
./methodatlas -ai -ai-confidence \
  -ai-provider ollama \
  src/test/java
```

**Additional output columns (CSV):** `ai_security_relevant`, `ai_display_name`,
`ai_tags`, `ai_reason` — and optionally `ai_confidence` with `-ai-confidence`.

See [AI Enrichment](ai-guide.md) for provider configuration, taxonomy options,
and confidence score interpretation.

---

## Mode 3 — Manual AI workflow

A two-phase workflow for environments where direct API access is not possible:
air-gapped networks, regulated environments with strict egress controls, or teams
that interact with an AI system through a supervised chat interface.

**Phase 1 — Prepare** generates prompt files; no output CSV is produced.

```bash
./methodatlas -manual-prepare ./work ./responses src/test/java
```

This creates one work file per test class in `./work/` and empty response
placeholders in `./responses/`.

**Between phases:** open each `.work` file, copy the `AI PROMPT` block into your
AI chat interface, and paste the response into the corresponding `.response.txt`
file in `./responses/`.

**Phase 2 — Consume** reads the filled response files and emits the enriched CSV.

```bash
./methodatlas -manual-consume ./work ./responses src/test/java
```

Classes whose response file is absent or empty are emitted with blank AI columns;
the scan does not fail.

!!! tip "Work and response directories"
    The two directory arguments may point to the same path if you prefer a single
    working directory.

---

## Modifier — Source write-back (`-apply-tags`)

Combine with **Mode 2** or **Mode 3 Phase 2** to insert AI-suggested
`@DisplayName` and `@Tag` annotations directly into the scanned source files.
No CSV is produced; a summary line is printed to stdout instead.

```bash
# Write-back with API AI
./methodatlas -ai -apply-tags \
  -ai-provider ollama \
  src/test/java

# Write-back after manual consume
./methodatlas -manual-consume ./work ./responses \
  -apply-tags src/test/java
```

**Summary output:**

```
Apply-tags complete: 12 annotation(s) added to 3 file(s)
```

!!! warning "Modifies source files"
    `-apply-tags` edits `.java` files in place using a lexical-preserving
    printer. Commit or back up your work before running. Only security-relevant
    methods receive new annotations.

See [AI Enrichment — apply-tags workflow](ai-guide.md#apply-tags-workflow) for
formatting guarantees and annotation placement details.

---

## Comparison table

| | Mode 1 | Mode 2 | Mode 3 |
|---|---|---|---|
| AI required | No | Yes | Via chat |
| Network access | No | Yes | No |
| Usable in air-gapped env | Yes | No | Yes |
| Output formats | CSV, text, SARIF | CSV, text, SARIF | CSV, text, SARIF |
| `-apply-tags` modifier | — | Yes | Yes (Phase 2) |
| Confidence scoring | — | Yes | Depends on model |

---

## YAML configuration

All modes can be driven by a shared configuration file to avoid repeating flags:

```yaml
outputMode: csv          # csv | plain | sarif
contentHash: false
ai:
  provider: openrouter
  model: google/gemini-flash-1.5
  apiKeyEnv: OPENROUTER_API_KEY
  confidence: false
```

```bash
./methodatlas -config methodatlas.yml src/test/java
```

CLI flags always override YAML values. See [CLI Reference](cli-reference.md) for
the full flag reference and YAML schema.
