# Confidence scoring

When `-ai-confidence` is passed alongside `-ai`, MethodAtlas instructs the model to attach a numeric confidence score to each method classification, enabling downstream filtering and audit-evidence quality control.

## When to use

Enable confidence scoring when you need to distinguish high-certainty security findings from borderline classifications — for example, when assembling an evidence package for a compliance audit or when gating a CI pipeline on only well-supported security test coverage.

See [AI Providers](providers.md) for provider options and the `-ai-confidence` flag description in [CLI reference](../cli-reference.md).

## What the score means

The score is a decimal in the range `0.0–1.0`. It reflects how certain the model is that a method genuinely tests a security property — **not** simply how confident it is that its answer is "yes".

A score of `0.0` does not indicate uncertainty. It means the model is confident the method is **not** security-relevant. The absence of a high score is itself information: the model read the test body and concluded it exercises no security property.

The model is instructed to prefer `securityRelevant=false` over returning a score below `0.5`. Low-confidence hits are suppressed rather than surfaced with a low score. In practice you will see scores clustered at `1.0`, `~0.7`, and `0.0`.

## Score reference

| Score range | Meaning                                                                                              | Example method                                         |
|-------------|------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| `1.0`       | The method name and body **explicitly and unambiguously** test a named security property.            | `testSQLInjectionBlocked` — asserts parameterised query rejects `' OR 1=1 --` with an exception |
| `~0.7`      | The method **clearly tests a security-adjacent concern**, but mapping to a specific tag requires inference from class name or surrounding tests. | `testUserInputHandling` — sanitises input but the class context is needed to confirm the injection-prevention intent |
| `~0.5`      | The classification is **plausible but ambiguous** — the method name or body is equally consistent with a non-security interpretation. | `testFormSubmit` — exercises a form handler that happens to include CSRF token validation alongside unrelated UI logic |
| `0.0`       | The method is **not security-relevant** — the model is confident it tests no security property.       | `testNullInput` — verifies that `format(null)` returns an empty string with no security implication |

## Why confidence matters

In regulated environments, test coverage evidence submitted to auditors needs to be defensible. Confidence scores let you:

- **Filter high-confidence findings** — export only rows where `ai_confidence >= 0.7` for your audit evidence package.
- **Flag borderline classifications for human review** — rows around `0.5` are candidates for manual inspection with [override files](overrides.md).
- **Track classification quality over time** — a sudden drop in average confidence across a module may indicate that test naming conventions have become unclear.

## Example

```bash
./methodatlas -ai -ai-confidence -ai-provider ollama -ai-model qwen2.5-coder:7b /path/to/tests
```

Output (CSV excerpt):

```csv
fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score,ai_confidence
com.acme.crypto.AesGcmTest,roundTrip_encryptDecrypt,18,,,true,SECURITY: crypto - AES-GCM round-trip,security;crypto,Verifies ciphertext and plaintext integrity under AES-GCM.,0.0,1.0
com.acme.auth.SessionTest,sessionToken_isRotatedAfterLogin,12,,,true,SECURITY: auth - session token rotation after login,security;auth,Session token is replaced on successful login to prevent fixation.,0.0,0.7
com.acme.util.DateFormatterTest,format_returnsIso8601,5,,,false,,,Test verifies date formatting output only.,0.0,0.0
```

The third row (`format_returnsIso8601`) has `ai_confidence=0.0` because the model is confident this method tests no security property — not because the model is uncertain.

## Filtering high-confidence findings

Because the output is plain CSV, standard shell tools work:

```bash
# Keep only rows where ai_confidence >= 0.7
./methodatlas -ai -ai-confidence /src | \
  awk -F',' 'NR==1 || ($11+0) >= 0.7'
```

Or in a YAML configuration file:

```yaml
ai:
  enabled: true
  confidence: true
  provider: ollama
  model: qwen2.5-coder:7b
```
