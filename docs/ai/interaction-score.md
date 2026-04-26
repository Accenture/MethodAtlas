# Interaction Score

## What is the interaction score?

Every test method classified by MethodAtlas AI receives an `ai_interaction_score` in addition to the
security-relevance classification. The score ranges from `0.0` to `1.0` and answers one specific
question:

> What fraction of this test's assertions only verify **interactions** (that methods were called,
> in what order, with what arguments) rather than **outcomes** (return values, computed state,
> observable side effects)?

| Score | Meaning |
|---|---|
| `1.0` | Every assertion is an interaction check; the test contains **no** assertion on any output value, state change, or observable result |
| `0.5` | Mixed: some real-output assertions alongside interaction-only checks |
| `0.0` | All assertions verify actual outputs or state; no interaction-only checks |

The score applies regardless of the mocking framework in use (Mockito, EasyMock, WireMock, etc.).

## Why this matters for security

A test with a high interaction score is a **Potemkin village**: it looks like a test, it counts as a
test, CI passes — but it provides no evidence that the code under test produces *correct output*.
The test only verifies that certain methods were called.

This is especially dangerous in the security domain:

- A test that calls `verify(passwordEncoder, times(1)).encode(rawPassword)` proves that the encoder
  was *invoked*. It does **not** prove that the encoded value is stored correctly, that the original
  plaintext is discarded, or that authentication logic accepts only the encoded form.
- A test that calls `verify(auditLog).record(event)` proves that the audit call was made. It says
  nothing about whether the event contains the right data or reaches a durable store.
- A test that verifies `mockRepository.save(entity)` was called does not test whether the saved
  entity has the correct security attributes.

In every case, the test creates a **false sense of coverage**. Standard code coverage tools report
the lines as covered. Security reviewers see a test named `shouldStoreEncodedPassword`. The placebo
effect is complete.

## Why standard tooling cannot catch this

| Tool | What it measures | Gap |
|---|---|---|
| **JaCoCo** | Line and branch coverage | Does not distinguish *which* assertions were made; a `verify()` call covers the same lines as an `assertEquals()` |
| **PIT (mutation testing)** | Whether surviving mutants are detectable by tests | Partially effective — PIT catches some weak assertions, but loose matchers (`any()`, `anyString()`) let many mutants survive silently |
| **PMD / Checkstyle** | Code style and structural rules | Rule-based; cannot reason semantically about whether an assertion tests an outcome |
| **SpotBugs** | Known bug patterns | Does not model assertion semantics |

None of these tools can tell you that a test asserting `verify(service).process(arg)` is
semantically weaker than one asserting `assertEquals(expectedResult, service.process(arg))`. The
AI can, because it reads the test body and understands what each assertion is actually checking.

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

**SARIF:** stored in `properties.aiInteractionScore`.

## Using the score in CI

A score of `1.0` is the strongest signal that a test is a placebo. A practical threshold depends on
your project's test style — some tests are legitimately interaction-only (e.g. verifying that an
event bus was notified). Use the score as a starting point for manual review, not as an automated
gate without tuning.

### Extract high-score security tests for review

```bash
# Print security-relevant tests with interaction score ≥ 0.8
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

The delta report will flag any method whose `ai_interaction_score` changed between runs.

## Interpreting results

A high score on a **non-security** test is usually a code-smell but not a safety issue. A high
score on a test tagged `security` or classified `ai_security_relevant=true` is a **meaningful
red flag**: you have a test whose security claim rests entirely on the observation that certain
methods were called.

Recommended remediation:
1. Add an `assertEquals` / `assertThat` on the actual output or final state.
2. If the interaction is genuinely the only observable (e.g. an audit notification), document why
   in a `@DisplayName` so reviewers understand the intent.
3. Consider adding a companion test that verifies the outcome through a higher-level assertion.
