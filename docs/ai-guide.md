# AI enrichment guide

MethodAtlas can optionally send each parsed test class to an AI provider for security classification. This guide explains how the AI subsystem works, which providers are supported, how to interpret and use the confidence score, and how to run the manual workflow when direct API access is unavailable.

## How AI enrichment works

When `-ai` is enabled, MethodAtlas:

1. **Scans and parses** source files deterministically (no AI involved at this stage)
2. **Builds a prompt** for each test class containing:
   - The security taxonomy that controls which tags are allowed
   - The exact list of JUnit test methods discovered by the parser
   - The complete class source as context
3. **Sends the prompt** to the configured provider
4. **Merges the response** into the emitted output — one AI suggestion per test method

The model is explicitly instructed to classify only the methods that the parser found. It cannot invent methods or skip methods from the list. This keeps the analysis deterministic at the structural level while still benefiting from AI for semantic interpretation.

If classification fails for a class (network error, timeout, malformed response), MethodAtlas logs a warning and continues scanning. The affected class appears in the output with empty AI columns.

## Supported providers

| Provider | Value | Authentication |
| --- | --- | --- |
| Auto (default) | `auto` | Tries Ollama first, then falls back to an API-key provider |
| Ollama (local) | `ollama` | None required |
| OpenAI | `openai` | API key |
| OpenRouter | `openrouter` | API key |
| Anthropic | `anthropic` | API key |

Select a provider with `-ai-provider <value>`. Provider values are case-insensitive.

### Auto mode

`auto` first probes the local Ollama endpoint (`http://localhost:11434`). If Ollama is reachable, it is used. If Ollama is not available and an API key has been configured, an OpenAI-compatible provider is used instead.

### Local inference with Ollama

Running a local model avoids sending proprietary source code to external services:

```bash
./methodatlas -ai -ai-provider ollama -ai-model qwen2.5-coder:7b /path/to/tests
```

The model name must match one already downloaded in your Ollama installation. The default model (`qwen2.5-coder:7b`) is suitable for code classification tasks and runs on consumer hardware.

### Cloud providers

```bash
# OpenRouter
export OPENROUTER_API_KEY=sk-...
./methodatlas -ai -ai-provider openrouter \
  -ai-api-key-env OPENROUTER_API_KEY \
  -ai-model stepfun/step-3.5-flash:free \
  /path/to/tests

# Anthropic
export ANTHROPIC_API_KEY=sk-ant-...
./methodatlas -ai -ai-provider anthropic \
  -ai-api-key-env ANTHROPIC_API_KEY \
  -ai-model claude-3-haiku-20240307 \
  /path/to/tests
```

## AI confidence scoring

### What it is

When `-ai-confidence` is passed alongside `-ai`, the prompt instructs the model to include a confidence score for each method classification. The score appears in the `ai_confidence` CSV column (or `AI_CONFIDENCE=` token in plain mode).

The score is a decimal in the range `0.0–1.0` and reflects how certain the model is that a method genuinely tests a security property, not just how confident it is that the answer is "yes".

### Interpreting the score

| Score range | Meaning |
| --- | --- |
| `1.0` | The method name and body **explicitly and unambiguously** test a named security property — authentication, authorisation, encryption, injection prevention, access control, etc. |
| `~0.7` | The method **clearly tests a security-adjacent concern**, but mapping it to a specific security property requires inference from the class name, surrounding test methods, or domain context |
| `~0.5` | The classification is **plausible but ambiguous** — the method name or body is equally consistent with a non-security interpretation |
| `0.0` | The method was classified as **not security-relevant** |

The model is instructed to prefer `securityRelevant=false` over returning a score below `0.5`. This means low-confidence hits are suppressed rather than surfaced with a low score.

### Why confidence matters

In regulated environments, test coverage evidence submitted to auditors needs to be defensible. A blanket "the AI said so" argument is weak. Confidence scores let you:

- **Filter high-confidence findings** — export only rows where `ai_confidence >= 0.7` for your audit evidence package
- **Flag borderline classifications for human review** — rows with `ai_confidence` around `0.5` are candidates for manual inspection
- **Track classification quality over time** — a sudden drop in average confidence across a module may indicate that test naming conventions have become unclear

### Example

```bash
./methodatlas -ai -ai-confidence -ai-provider ollama /path/to/tests
```

Output (CSV excerpt):

```csv
fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_confidence
com.acme.crypto.AesGcmTest,roundTrip_encryptDecrypt,18,,true,SECURITY: crypto - AES-GCM round-trip,security;crypto,Verifies ciphertext and plaintext integrity under AES-GCM.,1.0
com.acme.auth.SessionTest,sessionToken_isRotatedAfterLogin,12,,true,SECURITY: auth - session token rotation after login,security;auth,Session token is replaced on successful login to prevent fixation.,0.7
com.acme.util.DateFormatterTest,format_returnsIso8601,5,,false,,,Test verifies date formatting output only.,0.0
```

### Filtering high-confidence findings

Because the output is plain CSV, standard shell tools work:

```bash
# Keep only rows where ai_confidence >= 0.7 (requires mlr or awk)
./methodatlas -ai -ai-confidence /src | \
  awk -F',' 'NR==1 || ($9+0) >= 0.7'
```

## Security taxonomy

The AI is constrained to a **closed tag set** so that providers cannot invent arbitrary categories. The built-in taxonomy covers:

| Tag | Meaning |
| --- | --- |
| `security` | Umbrella tag — every security-relevant method must carry this |
| `auth` | Authentication: identity verification, login, token handling |
| `access-control` | Authorisation: permission checks, role enforcement |
| `crypto` | Cryptographic operations: encryption, signing, key derivation |
| `input-validation` | Input sanitisation, bounds checking, format enforcement |
| `injection` | SQL injection, command injection, LDAP injection, etc. |
| `data-protection` | PII handling, masking, data-at-rest controls |
| `logging` | Audit logging, sensitive data in logs, log injection |
| `error-handling` | Error responses, exception leakage, fail-safe defaults |
| `owasp` | Explicit OWASP Top 10 or ASVS scenario coverage |

### Taxonomy modes

MethodAtlas ships two built-in taxonomy variants:

- **`default`** — descriptive, human-readable; suitable for teams building familiarity with the taxonomy
- **`optimized`** — more compact; reduces prompt size and tends to improve classification reliability with smaller models

Select with `-ai-taxonomy-mode optimized`.

### Custom taxonomy

To align the taxonomy with your organisation's internal controls framework, supply an external taxonomy file:

```bash
./methodatlas -ai -ai-taxonomy /path/to/taxonomy.txt /path/to/tests
```

The file contents replace the built-in taxonomy text verbatim. When both `-ai-taxonomy` and `-ai-taxonomy-mode` are provided, the external file takes precedence.

## Manual AI workflow

Some teams can access an AI chat window (ChatGPT, Claude, Copilot, etc.) but cannot reach an AI API directly — for example, because corporate security policy blocks outbound API calls, or because there is no budget for API credentials.

MethodAtlas supports this through a two-phase **manual AI workflow** that replaces automated API communication with operator-mediated interaction.

### Overview

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

The final CSV is identical in format to the automated flow.

### Phase 1 — Prepare

```bash
./methodatlas -manual-prepare ./work ./responses /path/to/tests
```

MethodAtlas scans the source tree and, for each test class, writes:

1. **`<workdir>/<fqcn>.txt`** — the work file, containing operator instructions and the complete AI prompt (taxonomy definition, method list, full class source)
2. **`<responsedir>/<fqcn>.response.txt`** — an empty placeholder for the AI response; if it already exists and contains content from a previous run, it is left untouched

Both directories are created automatically if they do not exist. They may be the same path.

No CSV output is produced during the prepare phase.

### Phase 2 — Consume

After pasting AI responses into the pre-created `.response.txt` files:

```bash
./methodatlas -manual-consume ./work ./responses /path/to/tests
```

For each test class, MethodAtlas reads `<fqcn>.response.txt`:

- **File contains AI JSON** — the first JSON object is extracted (surrounding prose from the chat window is silently ignored), parsed, and merged into the output
- **File is empty or absent** — the row is emitted with blank AI columns; the scan continues without error

Processing is incremental: you can run consume after filling in each response rather than waiting until all classes are processed.

### Work file format

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
     (created empty alongside this work file — do not rename it).
  4. Repeat for all other work files.
  5. After all responses are saved, run the consume phase:
       java -jar methodatlas.jar -manual-consume <workdir> <responsedir> <source-roots...>
================================================================================

--- BEGIN AI PROMPT ---
You are analyzing a single JUnit 5 test class...
(full prompt with taxonomy, method list, and class source)
--- END AI PROMPT ---
```

### Taxonomy and confidence in manual mode

The same taxonomy flags apply in manual mode:

```bash
./methodatlas \
  -ai-taxonomy-mode optimized \
  -manual-prepare ./work ./responses \
  /path/to/tests
```

To include confidence instructions in the work file prompts (so the model you interact with manually is asked to provide a score), pass `-ai-confidence` before `-manual-prepare`:

```bash
./methodatlas \
  -ai-confidence \
  -manual-prepare ./work ./responses \
  /path/to/tests
```

The consume phase will then read the `confidence` field from each response file automatically.
