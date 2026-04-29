# Interaction score

The interaction score quantifies how much of a test's assertion weight rests on interaction verification (method-was-called) rather than outcome verification (correct value was produced), enabling MethodAtlas to flag security tests that provide false confidence.

## When to use

Examine the interaction score for any security test where you need to know whether the test actually checks a security outcome, or merely checks that a security-related method was invoked. Standard code coverage tools report both types identically; the interaction score distinguishes them.

## What the score measures

Every test method classified by MethodAtlas AI receives an `ai_interaction_score` in addition to the security-relevance classification. The score ranges from `0.0` to `1.0` and answers one specific question:

> What fraction of this test's assertions only verify **interactions** (that methods were called, in what order, with what arguments) rather than **outcomes** (return values, computed state, observable side effects)?

| Score | Meaning                                                                                           |
|-------|---------------------------------------------------------------------------------------------------|
| `1.0` | Every assertion is an interaction check; the test contains **no** assertion on any output value, state change, or observable result |
| `0.5` | Mixed: some real-output assertions alongside interaction-only checks                              |
| `0.0` | All assertions verify actual outputs or state; no interaction-only checks                        |

The score applies regardless of the mocking framework in use (Mockito, EasyMock, WireMock, etc.).

## Why this matters for security

A test with a high interaction score is a **placebo test**: it looks like a test, CI passes — but it provides no evidence that the code under test produces correct output. The test only verifies that certain methods were called.

**A test with 100% branch coverage and interaction score 1.0 is a placebo test — it gives false confidence.** Standard code coverage tools report the lines as covered. Security reviewers see a test named `shouldStoreEncodedPassword`. The test passes. Nothing is actually being verified about the encoded password.

This is especially dangerous in the security domain:

- A test that calls `verify(passwordEncoder, times(1)).encode(rawPassword)` proves that the encoder was *invoked*. It does **not** prove that the encoded value is stored correctly, that the original plaintext is discarded, or that authentication logic accepts only the encoded form.
- A test that calls `verify(auditLog).record(event)` proves that the audit call was made. It says nothing about whether the event contains the right data or reaches a durable store.
- A test that verifies `mockRepository.save(entity)` was called does not test whether the saved entity has the correct security attributes.

## Before and after: what the difference looks like

**Placebo test — interaction score 1.0:**

```java
@Test
void shouldEncodePasswordOnRegistration() {
    userService.register("alice", "secret");
    verify(passwordEncoder, times(1)).encode("secret");
    // Verifies encoder was called. Does NOT check the encoded value was stored.
}
```

**Outcome test — interaction score 0.0:**

```java
@Test
void shouldEncodePasswordOnRegistration() {
    String encoded = "$2a$10$...";
    when(passwordEncoder.encode("secret")).thenReturn(encoded);
    userService.register("alice", "secret");
    assertEquals(encoded, userRepository.findByUsername("alice").getPasswordHash());
    // Verifies the encoded value was stored in the correct field.
}
```

Both tests cover the same lines. JaCoCo reports 100% branch coverage for both. The interaction score distinguishes them.

## Why standard tooling cannot catch this

| Tool            | What it measures                              | Gap                                                                                                       |
|-----------------|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| **JaCoCo**      | Line and branch coverage                      | Does not distinguish *which* assertions were made; a `verify()` call covers the same lines as `assertEquals()` |
| **PIT**         | Whether surviving mutants are detectable by tests | Partially effective — PIT catches some weak assertions, but loose matchers (`any()`, `anyString()`) let many mutants survive silently |
| **PMD / Checkstyle** | Code style and structural rules          | Rule-based; cannot reason semantically about whether an assertion tests an outcome                        |
| **SpotBugs**    | Known bug patterns                            | Does not model assertion semantics                                                                        |

None of these tools can tell you that a test asserting `verify(service).process(arg)` is semantically weaker than one asserting `assertEquals(expectedResult, service.process(arg))`. The AI can, because it reads the test body and understands what each assertion is actually checking.

## Column in output

When AI enrichment is enabled, every row in CSV and plain-text output carries the score:

**CSV:**
```
fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score
com.acme.AuthTest,shouldValidatePassword,8,security,,true,SECURITY: ...,security;auth,Validates...,0.0
com.acme.AuthTest,shouldInvokeEncoder,5,security,,true,SECURITY: ...,security;auth,Calls encoder.,1.0
```

**Plain text:**
```
com.acme.AuthTest, shouldInvokeEncoder, LOC=5, TAGS=security, AI_SECURITY=true, ..., AI_INTERACTION_SCORE=1.0
```

**SARIF:** stored in `properties.aiInteractionScore`. When the score is ≥ 0.8, MethodAtlas also emits a dedicated `security-test/placebo` result at level `warning` alongside the primary security finding. This second result is independently filterable in GitHub Code Scanning, VS Code SARIF Viewer, and any SARIF-compatible platform — teams can configure a policy that blocks PRs containing new placebo security tests without affecting other findings. See [SARIF output format](../output-formats.md#rule-ids) for the full rule definition.

## Using the score in CI

A score of `1.0` is the strongest signal that a test is a placebo. A practical threshold depends on your project's test style — some tests are legitimately interaction-only (e.g. verifying that an event bus was notified). Use the score as a starting point for manual review, not as an automated gate without tuning.

### Extract high-score security tests for review

```bash
# Print security-relevant tests with interaction score >= 0.8
./methodatlas -ai -security-only src/test/java \
  | awk -F',' 'NR==1 || $10+0 >= 0.8' \
  > weak-security-tests.csv
```

### Delta: detect when interaction score worsens

```bash
# Capture before
./methodatlas -ai -content-hash src/test/java > before.csv

# ... after changes ...

# Capture after and diff
./methodatlas -ai -content-hash src/test/java > after.csv
./methodatlas -diff before.csv after.csv
```

The delta report flags any method whose `ai_interaction_score` changed between runs.

## Interpreting results

A high score on a **non-security** test is usually a code smell but not a safety issue. A high score on a test tagged `security` or classified `ai_security_relevant=true` is a **meaningful red flag**: you have a test whose security claim rests entirely on the observation that certain methods were called.

Recommended remediation:

1. Add an `assertEquals` / `assertThat` on the actual output or final state.
2. If the interaction is genuinely the only observable (e.g. an audit notification), document why in a `@DisplayName` so reviewers understand the intent.
3. Consider adding a companion test that verifies the outcome through a higher-level assertion.
