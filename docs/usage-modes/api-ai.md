# API AI Enrichment

API AI enrichment scans test sources and automatically submits each class to a configured AI provider for security classification, returning per-method relevance, taxonomy tags, a suggested display name, and a human-readable rationale.

## When to use this mode

- Your scan host has direct network access to an AI provider API (cloud-hosted or internal Ollama).
- You want automatic, hands-free security classification as part of a nightly or PR CI pipeline.
- You have an API key for a supported provider (OpenAI, Anthropic, Azure OpenAI, Mistral, Groq, xAI, GitHub Models, OpenRouter) or a local Ollama instance.
- You need the full AI enrichment columns (`ai_security_relevant`, `ai_tags`, `ai_reason`, `ai_interaction_score`) in your CSV output.

If direct API access is not permitted from the scan host, use the [Manual AI workflow](manual.md) instead.

## How it works

1. MethodAtlas scans and parses source files deterministically.
2. For each discovered test class it builds a prompt containing the security
   taxonomy, the list of test method names, and the complete class source.
3. The prompt is sent to the configured provider.
4. The provider's response is merged into the CSV output.

If classification fails for a class (network error, timeout, malformed response),
MethodAtlas logs a warning and continues. The affected class appears in the output
with blank AI columns.

!!! info "What is transmitted to the provider"
    Only the test class source file is submitted. Production source code,
    configuration files, and all other project content are never read or
    transmitted. See [AI Enrichment overview](../ai/index.md) for the complete data scope statement.
    For environments where external API calls are not permitted, use the
    [Manual AI workflow](manual.md) instead.

## Local inference — Ollama

[Ollama](https://ollama.com) runs entirely on your machine; no API key is required.

```bash
# Pull a model first
ollama pull qwen2.5-coder:7b

# Run MethodAtlas with Ollama
./methodatlas -ai \
  -ai-provider ollama \
  -ai-model qwen2.5-coder:7b \
  src/test/java
```

When `-ai-provider auto` (the default) is used, MethodAtlas probes Ollama first
and falls back to an API-key provider if Ollama is unreachable.

## Hosted providers

### Azure OpenAI

Azure OpenAI runs inside your organization's Azure tenant. Data does not leave the tenant boundary.

```bash
export AZURE_OPENAI_KEY=<your-key>
./methodatlas -ai \
  -ai-provider azure_openai \
  -ai-base-url https://contoso.openai.azure.com \
  -ai-model gpt-4o-prod \
  -ai-api-key-env AZURE_OPENAI_KEY \
  src/test/java
```

`model` is the **deployment name** as configured in the Azure portal, not the underlying model family name. See [Providers — Azure OpenAI](../ai/providers.md#azure-openai-corporate-cloud-inference) for credential setup instructions.

### OpenRouter

```bash
export OPENROUTER_API_KEY=sk-...
./methodatlas -ai \
  -ai-provider openrouter \
  -ai-api-key-env OPENROUTER_API_KEY \
  -ai-model google/gemini-flash-1.5 \
  src/test/java
```

### OpenAI

```bash
export OPENAI_API_KEY=sk-...
./methodatlas -ai \
  -ai-provider openai \
  -ai-api-key-env OPENAI_API_KEY \
  -ai-model gpt-4o-mini \
  src/test/java
```

### Anthropic

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./methodatlas -ai \
  -ai-provider anthropic \
  -ai-api-key-env ANTHROPIC_API_KEY \
  -ai-model claude-haiku-4-5-20251001 \
  src/test/java
```

!!! tip "Prefer `-ai-api-key-env` over `-ai-api-key`"
    Passing the key via an environment variable avoids leaking it into shell
    history and CI logs.

## Confidence scoring

```bash
./methodatlas -ai -ai-confidence \
  -ai-provider ollama \
  src/test/java
```

Adds an `ai_confidence` column (values `0.0`–`1.0`). See
[Confidence scoring](../ai/confidence.md) for the interpretation table.

## Output columns (CSV)

| Column              | Always present | Description                                                  |
|---------------------|----------------|--------------------------------------------------------------|
| `fqcn`              | Yes            | Fully qualified class name                                   |
| `method`            | Yes            | Method name                                                  |
| `loc`               | Yes            | Line count of the method declaration                         |
| `tags`              | Yes            | `@Tag` values already present in source                      |
| `display_name`      | Yes            | `@DisplayName` text present in source; empty if absent       |
| `ai_security_relevant` | Yes         | `true` or `false`                                            |
| `ai_display_name`   | Yes            | Suggested `@DisplayName` value from the AI                   |
| `ai_tags`           | Yes            | Semicolon-separated security taxonomy tags assigned by the AI |
| `ai_reason`         | Yes            | Human-readable rationale for the classification              |
| `ai_interaction_score` | Yes        | Test quality score from `0.0` (outcome assertions) to `1.0` (interaction-only) |
| `ai_confidence`     | With [`-ai-confidence`](../cli-reference.md#-ai-confidence) | AI certainty from `0.0` (uncertain) to `1.0` (certain) |

For a full description of each column and its interpretation, see [Guide for Security Teams](../concepts/for-security-teams.md) and [Output Formats](../output-formats.md).

## End-to-end scenario: nightly security classification

A team runs MethodAtlas nightly against their monolith's test suite, stores the enriched CSV as a CI artifact, and uses GitHub Code Scanning for security visibility.

```bash
# .github/workflows/security-scan.yml (excerpt)
- name: MethodAtlas AI enrichment
  env:
    ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
  run: |
    ./methodatlas \
      -ai \
      -ai-provider anthropic \
      -ai-model claude-haiku-4-5-20251001 \
      -ai-api-key-env ANTHROPIC_API_KEY \
      -ai-confidence \
      -content-hash \
      -emit-metadata \
      -sarif \
      src/test/java > security-tests.sarif

- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: security-tests.sarif

- name: Also export CSV for audit archive
  env:
    ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
  run: |
    ./methodatlas \
      -ai \
      -ai-provider anthropic \
      -ai-model claude-haiku-4-5-20251001 \
      -ai-api-key-env ANTHROPIC_API_KEY \
      -ai-confidence \
      -security-only \
      -content-hash \
      -emit-metadata \
      src/test/java > security-tests-$(date +%F).csv

- name: Upload CSV artifact
  uses: actions/upload-artifact@v4
  with:
    name: security-tests-${{ github.run_id }}
    path: security-tests-*.csv
```

The resulting SARIF is uploaded to GitHub Code Scanning. Each finding carries the `ai_reason` as its description and the `ai_interaction_score` embedded in the finding message, allowing the security team to triage directly from the GitHub Security tab.

The CSV artifact is retained as audit evidence. Its `content_hash` column allows the security team to verify that the scanned source was not modified between the scan and the review.

To understand the output columns, see [Guide for Security Teams](../concepts/for-security-teams.md). To compare two scan outputs over time, see [Delta Report](delta.md).

## Taxonomy and prompt tuning

```bash
# Compact taxonomy — better results with smaller models
./methodatlas -ai -ai-taxonomy-mode optimized src/test/java

# Load a custom taxonomy file
./methodatlas -ai -ai-taxonomy /path/to/taxonomy.txt src/test/java

# Skip classes larger than N characters
./methodatlas -ai -ai-max-class-chars 20000 src/test/java
```

See [Providers](../ai/providers.md) for the full provider configuration guide.
