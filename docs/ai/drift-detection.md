# Tag vs AI drift detection

Drift detection compares two independent classification sources — developer-applied `@Tag` annotations and AI classification — and surfaces disagreements that indicate stale annotations, missing tags, or tests that changed meaning after being labelled.

## When to use

Enable drift detection when your team uses `@Tag("security")` to drive CI gates, coverage counts, or audit evidence, and you want to verify that those tags remain accurate as the codebase evolves. It is especially valuable when onboarding AI classification onto an existing test suite that was tagged manually.

## How drift is computed

MethodAtlas compares two independent sources of security classification for each test method:

| Source                            | What it represents                                                                    |
|-----------------------------------|---------------------------------------------------------------------------------------|
| `@Tag("security")` in source code | A developer's (or tool's) intent at the time the annotation was written               |
| AI `securityRelevant` classification | The model's judgment based on reading the test body                                |

When these sources agree, there is nothing to report. When they disagree the discrepancy is called **drift**.

## Drift values

| Drift value | Meaning                                                                                                                       |
|-------------|-------------------------------------------------------------------------------------------------------------------------------|
| `none`      | Both sources agree — either both say security-relevant or neither does.                                                       |
| `tag-only`  | `@Tag("security")` is present but AI considers the method non-security-relevant. Tag-based CI gates and audit dashboards **over-count** security coverage. |
| `ai-only`   | AI classifies the method as security-relevant but no `@Tag("security")` annotation exists in source. Tag-based CI gates and coverage dashboards **silently miss** this test. |

### Concrete examples

**`none` — sources agree, both say security-relevant:**

```java
@Test
@Tag("security")
void testSQLInjectionBlocked() {
    // asserts that a parameterised query rejects ' OR 1=1 --
    assertThrows(InvalidInputException.class, () -> dao.find("' OR 1=1 --"));
}
```

AI output: `securityRelevant=true, ai_tags=security;injection`. Both the `@Tag` annotation and the AI agree. `tag_ai_drift=none`.

**`tag-only` — tag present, AI disagrees:**

```java
@Test
@Tag("security")
void testAuditLogFormat() {
    // Only checks that log message matches a string pattern; no security property verified
    String msg = auditLogger.format(event);
    assertEquals("AUDIT [INFO]: user login", msg);
}
```

AI output: `securityRelevant=false`. The test asserts a log format but not a security property (e.g., it does not verify that a login failure is logged, or that sensitive data is absent). The `@Tag("security")` was applied when the test was created alongside genuine security tests, but the test body does not exercise a security concern. `tag_ai_drift=tag-only`.

**`ai-only` — AI classifies as security-relevant, no tag:**

```java
@Test
void testRateLimitingEnforced() {
    // Sends 11 requests and asserts the 11th returns HTTP 429
    for (int i = 0; i < 11; i++) {
        Response r = client.get("/api/resource");
        if (i < 10) assertEquals(200, r.status());
        else assertEquals(429, r.status());
    }
}
```

AI output: `securityRelevant=true, ai_tags=security;auth`. The method clearly tests a security control (rate limiting / brute-force prevention) but has no `@Tag("security")`. This test is excluded from all tag-based security gates. `tag_ai_drift=ai-only`.

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

Or via YAML:

```yaml
driftDetect: true
```

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

## Deployment rationale

Drift detection is particularly valuable in the following scenarios:

- **Tag-based CI count gates** — a gate that requires "at least N security tests" is only meaningful if the tags are current. Running `-drift-detect` in every PR pipeline and failing on new drift entries ensures annotations stay synchronized with the code.
- **First-time AI scan on an existing codebase** — comparing AI results against existing `@Tag` annotations surfaces systematic tagging errors before they propagate further.
- **Regulated handover** — audit evidence packages that include both tag counts and AI classification can include a drift report to demonstrate that the two sources were reconciled.
- **Refactoring safety net** — large-scale test refactoring may silently change what a test actually asserts. Drift from `none` to `ai-only` after a refactor indicates the test may have lost its security-relevant assertion.
