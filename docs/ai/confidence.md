# Confidence scoring

## What it is

When `-ai-confidence` is passed alongside `-ai`, the prompt instructs the model to include a confidence score for each method classification. The score appears in the `ai_confidence` CSV column (or `AI_CONFIDENCE=` token in plain mode).

The score is a decimal in the range `0.0–1.0` and reflects how certain the model is that a method genuinely tests a security property — not just how confident it is that the answer is "yes".

## Interpreting the score

| Score range | Meaning |
|---|---|
| `1.0` | The method name and body **explicitly and unambiguously** test a named security property — authentication, authorisation, encryption, injection prevention, access control, etc. |
| `~0.7` | The method **clearly tests a security-adjacent concern**, but mapping it to a specific security property requires inference from the class name, surrounding test methods, or domain context. |
| `~0.5` | The classification is **plausible but ambiguous** — the method name or body is equally consistent with a non-security interpretation. |
| `0.0` | The method was classified as **not security-relevant**. |

The model is instructed to prefer `securityRelevant=false` over returning a score below `0.5`. Low-confidence hits are suppressed rather than surfaced with a low score.

## Why confidence matters

In regulated environments, test coverage evidence submitted to auditors needs to be defensible. Confidence scores let you:

- **Filter high-confidence findings** — export only rows where `ai_confidence >= 0.7` for your audit evidence package.
- **Flag borderline classifications for human review** — rows around `0.5` are candidates for manual inspection.
- **Track classification quality over time** — a sudden drop in average confidence across a module may indicate that test naming conventions have become unclear.

## Example

```bash
./methodatlas -ai -ai-confidence -ai-provider ollama /path/to/tests
```

Output (CSV excerpt):

```csv
fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score,ai_confidence
com.acme.crypto.AesGcmTest,roundTrip_encryptDecrypt,18,,,true,SECURITY: crypto - AES-GCM round-trip,security;crypto,Verifies ciphertext and plaintext integrity under AES-GCM.,0.0,1.0
com.acme.auth.SessionTest,sessionToken_isRotatedAfterLogin,12,,,true,SECURITY: auth - session token rotation after login,security;auth,Session token is replaced on successful login to prevent fixation.,0.0,0.7
com.acme.util.DateFormatterTest,format_returnsIso8601,5,,,false,,,Test verifies date formatting output only.,0.0,0.0
```

## Filtering high-confidence findings

Because the output is plain CSV, standard shell tools work:

```bash
# Keep only rows where ai_confidence >= 0.7
./methodatlas -ai -ai-confidence /src | \
  awk -F',' 'NR==1 || ($11+0) >= 0.7'
```
