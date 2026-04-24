# Guide for Security Teams

This guide is written for security managers, compliance officers, and CISOs
who receive MethodAtlas output and need to interpret it, act on findings, and
incorporate results into audit evidence packages. It does not assume
familiarity with Java development or CI/CD tooling.

## What MethodAtlas produces

MethodAtlas reads a project's Java test source code and produces a structured
inventory of test methods that are security-relevant — methods written to
verify that the application correctly implements authentication, cryptography,
input validation, access control, and similar security properties.

The output is a table. Each row describes one test method. The columns fall
into two groups:

### Structural data (always present)

These columns are derived directly from the source code, without any AI
involvement. They are deterministic and do not change between runs unless
the source changes.

| Column | Meaning |
|---|---|
| `fqcn` | Fully qualified class name — the Java package and class that contains this test |
| `method` | The name of the test method |
| `loc` | Inclusive line count of the method declaration |
| `tags` | JUnit `@Tag` values declared in source (e.g. `security`, `auth`) |
| `content_hash` | SHA-256 fingerprint of the enclosing class source — enables revision traceability |

### AI enrichment (present when AI classification is enabled)

These columns are produced by an AI model that reads the test method body
and classifies it according to a security taxonomy.

| Column | Meaning |
|---|---|
| `ai_security_relevant` | `true` if the AI determined this method tests a security property; `false` otherwise |
| `ai_display_name` | A human-readable description of what the test is verifying (e.g. `SECURITY: auth — login rejects expired tokens`) |
| `ai_tags` | Security taxonomy tags assigned by the AI (e.g. `auth`, `crypto`, `injection`) |
| `ai_reason` | The AI's rationale for its classification — one or two sentences explaining why the method is or is not security-relevant |
| `ai_confidence` | The AI model's certainty in its classification, from `0.0` (uncertain) to `1.0` (certain) |
| `ai_interaction_score` | A measure of test quality; see below |
| `tag_ai_drift` | Present when drift detection is enabled; see below |

## Understanding the interaction score

The `ai_interaction_score` column measures a specific weakness in test design
that is particularly dangerous in the security domain. It answers the question:

> **Does this test verify the outcome, or does it only verify that certain
> methods were called?**

| Score | Meaning in plain English |
|---|---|
| `0.0` | The test checks the actual result — a return value, a thrown exception, a database state. This is the strongest form of security test. |
| `1.0` | The test only verifies that certain methods were called, without checking what they returned or what state they produced. |
| Values in between | Mixed: some outcome assertions alongside interaction-only checks. |

### Why this matters

Consider a test named `shouldStoreEncodedPassword`. If that test only
verifies that the password encoder was *called* — but does not check that
the encoded value was actually stored, that the plaintext was discarded, and
that the stored form is used for authentication — then it provides no real
security evidence. The test will still pass even if the encoding logic was
removed from production code, as long as the encoder method was still invoked.

This pattern is sometimes called a "placebo test" or "Potemkin village test":
it looks like a test, CI reports it as passing, code coverage tools count it
as covered — but it does not actually verify the security property.

Standard code coverage tools cannot detect this. MethodAtlas can, because
the AI reads the test body and understands what each assertion is actually
checking.

!!! tip "Action threshold"
    A score of `1.0` warrants immediate developer review. A score above `0.8`
    warrants review before the next release. Scores below `0.5` on security-
    relevant tests are generally acceptable.

## Understanding the confidence score

The `ai_confidence` score reflects how certain the AI model is about its
security-relevance classification. This is separate from the test quality
measured by the interaction score.

| Range | Interpretation |
|---|---|
| `0.8` – `1.0` | High confidence — the model is certain. Treat the classification as reliable. |
| `0.5` – `0.8` | Moderate confidence — the model is fairly certain but human review is advisable, particularly if the method is a high-stakes security control. |
| `0.0` – `0.5` | Low confidence — the AI is uncertain. Human review is required before including or excluding this method from an audit evidence package. |

## Understanding drift detection

When drift detection is enabled (`-drift-detect` flag), the output includes
a `tag_ai_drift` column for each method. It compares the `@Tag("security")`
annotation in the source code against the AI classification:

| Value | Meaning | Action |
|---|---|---|
| `none` | Source tag and AI agree. No action needed. | — |
| `tag-only` | Source has `@Tag("security")` but the AI does not consider it security-relevant. The annotation may be stale, or the AI may be wrong. | Review the method; update the tag or add an override. |
| `ai-only` | AI considers the method security-relevant but the source has no `@Tag("security")` annotation. The test covers a security property but is not labelled as such. | Consider adding `@Tag("security")` to the source, or document why the AI is incorrect. |

Drift is significant for audit purposes: dashboards and CI gates that rely on
source-level `@Tag("security")` annotations will silently miscount coverage if
drift exists and is not corrected.

## Prioritising findings

The following framework helps security reviewers decide which findings to act
on first:

| Priority | Condition | Recommended action |
|---|---|---|
| Critical | `ai_security_relevant=true` AND `ai_interaction_score >= 0.8` | Escalate to development team: the test is a placebo. Require outcome assertion before next release. |
| High | `ai_security_relevant=true` AND `ai_confidence < 0.5` | Manual review: the AI is uncertain. A qualified reviewer should determine whether the classification is correct. |
| Medium | `tag_ai_drift = tag-only` | Review: the `@Tag("security")` annotation may be stale, or the AI taxonomy may not cover this security domain. |
| Medium | `tag_ai_drift = ai-only` | Review: a security test may be unlabelled, which would cause it to be missed by tag-based reporting. |
| Low | `ai_security_relevant=true`, high confidence, low interaction score | Verify taxonomy tags are correct. No immediate action required. |

## Documenting accepted risks: the override file

When the security team reviews findings and reaches a conclusion about a
specific test method — whether the AI is correct, incorrect, or the risk is
accepted — those decisions should be recorded in the **override file**.

The override file is a YAML document stored in version control alongside the
test source. It records:

- Methods where the AI classification is incorrect (false positives and false
  negatives).
- Methods where the security team has accepted the risk of a weak test (with
  documented rationale).
- Methods that the security team has confirmed as correctly classified.

Each entry supports a free-text `note` field that is never emitted in any
output — it is an internal annotation for the security team:

```yaml
overrides:

  # AI missed this security-critical test — correct the false negative
  - fqcn: com.acme.crypto.AesGcmTest
    method: roundTrip_encryptDecrypt
    securityRelevant: true
    tags: [security, crypto]
    reason: "Verifies ciphertext integrity under AES-GCM — critical cryptographic test"
    note: "Confirmed by security team 2026-04-24 — alice@example.com"

  # Accepted risk: interaction-only test, but replacement is planned for next sprint
  - fqcn: com.acme.auth.LegacyAuthTest
    method: shouldCallEncoder
    securityRelevant: true
    tags: [security, auth]
    note: "Accepted risk 2026-04-24 — outcome assertion to be added in sprint 42 — bob@example.com"
```

Every change to the override file is visible in the version control diff,
creating a tamper-evident audit trail of all human classification decisions.

See [Classification Overrides](../ai/overrides.md) for the complete file
format reference.

## Viewing results in GitHub Code Scanning

When SARIF output is uploaded to GitHub Code Scanning, findings appear under
**Security → Code scanning** in the GitHub repository. Each finding includes:

- The rule ID (taxonomy tag assigned by MethodAtlas).
- The affected file and line number.
- The AI rationale as the finding description.
- The interaction score and confidence in the finding properties.

Findings can be filtered by rule, by file path, and by state (open, closed,
dismissed). Dismissed findings are retained as an audit trail.

For organisations without GitHub Advanced Security, findings are delivered as
inline annotations on pull request diffs — no separate dashboard is required.

## Executive summary template

The following structure can be used as a basis for a security test coverage
section in an audit evidence package or security review document:

---

**Security Test Coverage Summary**

*Prepared by:* [role] *Date:* [date] *Source revision:* [git commit SHA]

*Tool:* MethodAtlas [version], scan run as part of [CI pipeline / release process]

| Metric | Value |
|---|---|
| Total test methods scanned | [n] |
| Security-relevant test methods (AI-classified) | [n] |
| High-confidence classifications (≥ 0.8) | [n] |
| Placebo tests requiring review (interaction score ≥ 0.8) | [n] |
| Human-reviewed overrides in force | [n] |
| Drift findings (tag vs AI disagreement) | [n] |

*Security taxonomy coverage:* [list of taxonomy tags present, e.g. auth, crypto, injection, session]

*Open findings:* [brief description of any critical or high priority items above, or "None"]

*Artefacts retained:* [file names of SARIF and CSV outputs, with content hashes]

---

## Further reading

- [AI Interaction Score](../ai/interaction-score.md) — detailed explanation of the score and remediation guidance
- [Classification Overrides](../ai/overrides.md) — override file format and workflow
- [Tag vs AI Drift](../ai/drift-detection.md) — drift detection configuration and interpretation
- [Output Formats](../output-formats.md) — CSV, SARIF, and plain-text column reference
- [Compliance & Standards](../compliance.md) — framework-specific mapping (OWASP SAMM, ISO 27001, NIST SSDF, DORA)
