# API AI Enrichment

Scan test sources and automatically submit each class to an AI provider for
security classification. The provider analyses the full source of each class and
returns per-method security relevance, taxonomy tags, a suggested display name,
and a human-readable rationale.

**When to use:** Development workflows with direct network access to an AI API,
automated nightly classification jobs, or CI pipelines with a hosted AI gateway.

## How it works

1. MethodAtlas scans and parses source files deterministically.
2. For each discovered test class it builds a prompt containing the security
   taxonomy, the list of test method names, and the complete class source.
3. The prompt is sent to the configured provider.
4. The provider's response is merged into the CSV output.

If classification fails for a class (network error, timeout, malformed response),
MethodAtlas logs a warning and continues. The affected class appears in the output
with blank AI columns.

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
[AI Enrichment — Confidence scoring](../ai-guide.md#ai-confidence-scoring) for
the interpretation table.

## Output columns (CSV)

| Column | Description |
|---|---|
| `fqcn` | Fully qualified class name |
| `method` | Method name |
| `loc` | Line count of the method declaration |
| `tags` | `@Tag` values already present in source |
| `ai_security_relevant` | `true` or `false` |
| `ai_display_name` | Suggested `@DisplayName` value |
| `ai_tags` | Semicolon-separated security taxonomy tags |
| `ai_reason` | Human-readable rationale for the classification |
| `ai_confidence` | `0.0`–`1.0` (only with `-ai-confidence`) |

## Taxonomy and prompt tuning

```bash
# Compact taxonomy — better results with smaller models
./methodatlas -ai -ai-taxonomy-mode optimized src/test/java

# Load a custom taxonomy file
./methodatlas -ai -ai-taxonomy /path/to/taxonomy.txt src/test/java

# Skip classes larger than N characters
./methodatlas -ai -ai-max-class-chars 20000 src/test/java
```

See [AI Enrichment](../ai-guide.md) for the full provider configuration guide.
