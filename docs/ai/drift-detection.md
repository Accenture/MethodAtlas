# Tag vs AI drift detection

## Overview

MethodAtlas can compare two independent sources of security classification for each test method and surface disagreements automatically:

| Source | What it represents |
|---|---|
| `@Tag("security")` in source code | A developer's (or tool's) intent at the time the annotation was written |
| AI `securityRelevant` classification | The model's judgment based on reading the test body |

When these sources agree, there is nothing to report. When they disagree the discrepancy is called **drift**, and it falls into one of two categories:

| Drift value | Meaning |
|---|---|
| `tag-only` | `@Tag("security")` is present but AI considers the method non-security-relevant. The annotation may be stale, inaccurate, or copied from a nearby method. Tag-based CI gates and audit dashboards **over-count** security coverage. |
| `ai-only` | AI classifies the method as security-relevant but no `@Tag("security")` annotation exists in source. Tag-based CI gates and coverage dashboards **silently miss** this test. |
| `none` | Both sources agree — either both say security-relevant or neither does. |

## Why drift matters in regulated environments

Compliance audits that rely on tag-based test counts (e.g. "we have N tests tagged `security`") are only meaningful if the tags are accurate. Drift detection surfaces two audit risks:

1. **`tag-only` drift** — the tag may have been copied from a template, applied to the wrong method, or left in place after the test was refactored into a non-security concern. Auditors see more security coverage than exists.
2. **`ai-only` drift** — a developer wrote a test that genuinely exercises a security property but forgot to tag it, or the tagging policy was not enforced. Auditors see less security coverage than exists; missing the test may also mean it is excluded from security-specific CI gates.

## Enabling drift detection

### CSV and plain-text output

Add `-drift-detect` to any scan that also uses `-ai`:

```bash
./methodatlas -ai -drift-detect src/test/java
```

A `tag_ai_drift` column is appended to the end of every CSV row (after `ai_confidence` when that flag is also set). In plain-text output the field appears as `TAG_AI_DRIFT=`. The column is absent when `-drift-detect` is omitted so that scripts not expecting it are unaffected.

### SARIF output

Drift is **always included** in SARIF output when AI is enabled — no additional flag is needed. It appears as `properties.tagAiDrift` in each result. SARIF consumers (GitHub Code Scanning, SonarQube, Defect Dojo) handle unknown properties gracefully, so adding this field does not break existing integrations.

```bash
./methodatlas -ai -sarif src/test/java > scan.sarif
```

### GitHub Annotations

Drift notes are **always included** in the annotation message when AI is enabled and drift is detected for that method. No flag is needed. When a method has `tag-only` or `ai-only` drift, the annotation message gains a suffix such as:

- `· Drift: @Tag("security") present but AI disagrees — annotation may be stale`
- `· Drift: AI classifies as security-relevant but no @Tag("security") in source`

## Drift in the delta report

When two scans both include the `tag_ai_drift` column (i.e., both were run with `-ai -drift-detect`), the delta report includes `tag_ai_drift` in the set of compared fields. A transition from `none` to `ai-only` (or vice versa) will appear as a `~` (modified) entry with `[tag_ai_drift]` in the changed-fields summary.

## Reading a drift-enriched CSV

```text
fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score,tag_ai_drift
com.acme.AuthTest,testLoginSuccess,12,security,true,Login success path,auth;session,,0.1,none
com.acme.AuthTest,testLoginRateLimiting,18,,true,Rate limiting enforcement,auth;rate-limit,,0.0,ai-only
com.acme.AuthTest,testAuditLog,9,security,false,Audit log format check,,,0.0,tag-only
```

In this example:

- `testLoginSuccess` — tag and AI agree: security-relevant. No action needed.
- `testLoginRateLimiting` — AI identified a security test but no `@Tag("security")` was added. Consider adding the tag to include this test in tag-based CI gates.
- `testAuditLog` — `@Tag("security")` is present but AI considers it non-security-relevant. Review whether the tag should be removed or the test should be strengthened.

## YAML configuration

```yaml
driftDetect: true
```

This is equivalent to passing `-drift-detect` on the command line. Command-line flags always override YAML values.

## Deployment rationale

Drift detection is particularly valuable in the following scenarios:

- **Tag-based CI count gates** — a gate that requires "at least N security tests" is only meaningful if the tags are current. Running `-drift-detect` in every PR pipeline and failing on new drift entries ensures annotations stay synchronized with the code.
- **First-time AI scan on an existing codebase** — comparing AI results against existing `@Tag` annotations surfaces systematic tagging errors before they propagate further.
- **Regulated handover** — audit evidence packages that include both tag counts and AI classification can include a drift report to demonstrate that the two sources were reconciled.
- **Refactoring safety net** — large-scale test refactoring may silently change what a test actually asserts. Drift from `none` to `ai-only` after a refactor indicates the test may have lost its security-relevant assertion.
