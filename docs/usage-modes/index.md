# Usage Modes

MethodAtlas operates in three independent modes. Source write-back modifiers
can be layered on top of any mode that produces AI output or a reviewed CSV.

| Mode | AI required | Network | Output formats |
|---|---|---|---|
| [Static inventory](static-inventory.md) | No | No | CSV, plain text, SARIF, JSON |
| [API AI enrichment](api-ai.md) | Yes — via API | Yes | CSV, plain text, SARIF, JSON |
| [Manual AI workflow](manual.md) | Yes — via chat | No | CSV, plain text, SARIF, JSON |
| [Source write-back (AI)](apply-tags.md) | Combined with above | Depends | — |
| [Source write-back (CSV)](apply-tags-from-csv.md) | No | No | — |

Choose the mode that fits your infrastructure constraints:

- **No AI access at all** → Static inventory gives you the method list; tags can
  be added manually or in a later enrichment step.
- **Direct API access** → API AI enrichment is the simplest path; ten providers
  are supported (Ollama, OpenAI, Anthropic, Azure OpenAI, Mistral, Groq, xAI,
  GitHub Models, OpenRouter, and `auto`). See [AI Providers](../ai/providers.md).
- **Air-gapped or policy-restricted** → Manual AI workflow lets you use a
  supervised chat interface without any outbound network calls from the scan host.

## YAML configuration

All modes support a shared configuration file that avoids repeating flags:

```yaml
outputMode: csv          # csv | plain | sarif | json
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

CLI flags always override YAML values. See the [CLI Reference](../cli-reference.md)
for the full flag list and the complete YAML schema.
