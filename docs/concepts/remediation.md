# Developer Remediation Guide

MethodAtlas identifies security test methods and measures their quality.
This guide explains how to act on the findings: how to improve tests with
a high interaction score, how to add missing outcome assertions, and how to
write companion tests when an existing test's structure limits what can be
asserted.

The examples throughout this guide use JUnit 5 and Mockito — the frameworks
in widest use in the Java ecosystem — but the principles apply to any testing
framework.

## Understanding what needs to change

MethodAtlas produces two kinds of actionable findings for individual tests:

| Finding | Column | Remedy |
|---|---|---|
| Security-relevant test with high interaction score | `ai_interaction_score` close to `1.0` | Add outcome assertions; see below |
| Security-relevant test with low AI confidence | `ai_confidence` below `0.5` | Clarify intent with `@DisplayName`; verify classification with override file |
| Security-relevant test not tagged in source | `tag_ai_drift = ai-only` | Add `@Tag("security")` to the test method |
| Source-tagged test not classified as security-relevant | `tag_ai_drift = tag-only` | Review whether the tag is correct; remove or add an override entry |

This guide focuses on the first and most impactful category: tests with
`ai_interaction_score >= 0.8`.

## The interaction score in practice

A test with a score of `1.0` contains only interaction assertions — it
verifies that certain methods were called, in what order, or with what
arguments. It does not check what those methods returned, what state they
produced, or what the system did as an observable consequence.

For security properties, this means the test provides no evidence that the
property holds.

### Identifying affected tests

```bash
# Print security-relevant tests with interaction score >= 0.8
java -jar methodatlas.jar -ai -security-only src/test/java \
  | awk -F',' 'NR==1 || ($5=="true" && $9+0 >= 0.8)'
```

The `ai_reason` column explains what the AI observed in the test body. Read
it before making changes — it often identifies exactly which assertion is
missing.

## Remediation patterns

### Pattern 1: add an outcome assertion alongside an existing interaction check

This is the most common case. The test calls a method and verifies it was
invoked, but does not check the result.

**Before** (`ai_interaction_score = 1.0`):

```java
@Test
@Tag("security")
void shouldHashPasswordOnRegistration() {
    userService.register("alice", "password123");

    verify(passwordEncoder, times(1)).encode("password123");
}
```

The test proves the encoder was called. It does not prove the encoded value
was persisted, nor that the plaintext was discarded.

**After** (`ai_interaction_score = 0.0`):

```java
@Test
@Tag("security")
void shouldHashPasswordOnRegistration() {
    userService.register("alice", "password123");

    verify(passwordEncoder, times(1)).encode("password123");

    User saved = userRepository.findByUsername("alice");
    assertNotNull(saved, "User should be persisted");
    assertNotEquals("password123", saved.getPasswordHash(),
        "Plaintext must not be stored");
    assertTrue(passwordEncoder.matches("password123", saved.getPasswordHash()),
        "Stored hash must match original password");
}
```

The added assertions verify the observable outcome: the hash is stored, the
plaintext is absent.

### Pattern 2: replace a mock-return assertion with a real boundary check

Some tests mock a dependency's return value and then assert on the mock's
state rather than the system's response.

**Before** (`ai_interaction_score = 1.0`):

```java
@Test
@Tag("security")
void shouldRejectExpiredToken() {
    when(tokenValidator.validate(EXPIRED_TOKEN)).thenReturn(false);

    authService.authenticate(EXPIRED_TOKEN);

    verify(tokenValidator).validate(EXPIRED_TOKEN);
}
```

The test verifies that `validate` was called. It does not assert that
`authenticate` rejected the request.

**After** (`ai_interaction_score = 0.0`):

```java
@Test
@Tag("security")
void shouldRejectExpiredToken() {
    when(tokenValidator.validate(EXPIRED_TOKEN)).thenReturn(false);

    AuthenticationException ex = assertThrows(
        AuthenticationException.class,
        () -> authService.authenticate(EXPIRED_TOKEN),
        "Expired token must cause AuthenticationException"
    );
    assertEquals("TOKEN_EXPIRED", ex.getErrorCode());
    verify(tokenValidator).validate(EXPIRED_TOKEN);
}
```

The key change is `assertThrows`: the test now verifies that the service
rejected the request, not merely that a method was called.

### Pattern 3: assert the absence of sensitive data

Tests that verify audit logging or error handling often need to assert
that sensitive data does not appear in output.

**Before** (`ai_interaction_score = 0.8`):

```java
@Test
@Tag("security")
void shouldLogFailedLoginAttempt() {
    authService.login("alice", "wrongPassword");

    verify(auditLogger).log(any(AuditEvent.class));
}
```

The test proves the logger was called. It does not verify the event's content
or that the password was not included in the log record.

**After** (`ai_interaction_score = 0.0`):

```java
@Test
@Tag("security")
void shouldLogFailedLoginAttemptWithoutExposingPassword() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);

    authService.login("alice", "wrongPassword");

    verify(auditLogger).log(captor.capture());
    AuditEvent event = captor.getValue();

    assertEquals(AuditEvent.Type.LOGIN_FAILURE, event.getType());
    assertEquals("alice", event.getUsername());
    assertFalse(event.toString().contains("wrongPassword"),
        "Password must not appear in the audit record");
}
```

The `ArgumentCaptor` captures the actual event and allows asserting on its
content, not just on the fact that the logger was called.

### Pattern 4: write a companion test for the negative case

Some tests are structurally sound but cover only the happy path of a security
control. The security property is only fully tested when the negative case is
also covered.

**Existing test** (adequate, score = 0.1):

```java
@Test
@Tag("security")
void shouldAllowAdminToDeleteUser() {
    assertDoesNotThrow(() ->
        userService.deleteUser(ADMIN_PRINCIPAL, TARGET_USER_ID));
}
```

**Companion test** (negative case):

```java
@Test
@Tag("security")
@DisplayName("SECURITY: access-control — non-admin cannot delete users")
void shouldForbidNonAdminFromDeletingUser() {
    AccessDeniedException ex = assertThrows(
        AccessDeniedException.class,
        () -> userService.deleteUser(REGULAR_PRINCIPAL, TARGET_USER_ID),
        "Regular user must not be permitted to delete other users"
    );
    assertNotNull(ex.getMessage());
}
```

Without the companion test, a regression that removes the authorisation check
entirely would cause the positive test to still pass (the operation succeeds,
which is exactly what the test asserts). The negative test would catch that
regression immediately.

### Pattern 5: clarify intent when the interaction is genuinely the only observable

Some security properties can only be verified by observing that a method
was called, because the system under test does not expose the downstream
outcome directly. In these cases, the high interaction score is a false
positive — the test is as strong as the architecture permits.

Document this clearly in the `@DisplayName` annotation so that future
reviewers understand the constraint:

```java
@Test
@Tag("security")
@DisplayName("SECURITY: logging — audit notification is dispatched on "
    + "privilege escalation (outcome verified by audit-service integration tests)")
void shouldDispatchAuditEventOnPrivilegeEscalation() {
    adminService.elevatePrivileges(REQUEST);

    verify(auditDispatcher).dispatch(argThat(event ->
        event.getType() == AuditEvent.Type.PRIVILEGE_ESCALATION
        && event.getActorId().equals(REQUEST.getActorId())
    ));
}
```

Then add an entry to the override file to suppress the finding permanently,
with a rationale that points to where the outcome is verified:

```yaml
overrides:
  - fqcn: com.acme.admin.AdminServiceTest
    method: shouldDispatchAuditEventOnPrivilegeEscalation
    securityRelevant: true
    tags: [security, logging]
    note: "Interaction-only by design: audit delivery is verified in
           AuditServiceIntegrationTest. Accepted 2026-04-25 — alice."
```

## Documenting security intent with @DisplayName

The `ai_display_name` column suggests a `@DisplayName` value. Adding it to
the test method improves both AI classification accuracy on future runs and
human readability in test reports.

The standard format MethodAtlas uses for its suggestions:
`SECURITY: <tag> — <what the test verifies>`

```java
@Test
@Tag("security")
@DisplayName("SECURITY: auth — login is rejected when account is locked")
void shouldRejectLoginForLockedAccount() {
    // ...
}
```

A `@DisplayName` that explicitly names the security property makes it
possible to scan the test class and understand its security coverage at a
glance, without reading every method body.

## Applying AI-suggested annotations automatically

For teams with many tests to update, the `-apply-tags` mode writes AI-
generated `@DisplayName` and `@Tag` annotations back to the source files
without manual editing:

```bash
java -jar methodatlas.jar \
  -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
  -apply-tags \
  src/test/java
```

Review the changes with `git diff` before committing. The write-back adds
annotations to security-relevant methods that lack them; it does not modify
existing annotations or test logic.

See [Source Write-back](../usage-modes/apply-tags.md) for the full
`-apply-tags` reference.

## Applying reviewed CSV decisions to source files

For teams that require a human sign-off before any source file is touched,
the `-apply-tags-from-csv` mode separates the review step from the write-back:

```bash
# 1. Produce a CSV with AI suggestions
java -jar methodatlas.jar \
  -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
  src/test/java > review.csv

# 2. Open review.csv, adjust the tags and display_name columns, save.

# 3. Replay the approved decisions into source
java -jar methodatlas.jar \
  -apply-tags-from-csv review.csv \
  -mismatch-limit 1 \
  src/test/java
```

The CSV is the complete desired state: every `@Tag` and `@DisplayName` on
every test method is driven entirely by the corresponding CSV row. Committing
`review.csv` alongside the annotated source gives the team a permanent,
reviewable record of each annotation decision.

The `-mismatch-limit 1` flag prevents the write-back from running if the
codebase has diverged from the reviewed CSV (a method was added or deleted
since the review), which guards against accidentally applying stale decisions.

See [Apply Tags from CSV](../usage-modes/apply-tags-from-csv.md) for the
full reference.

## Checklist for a remediation review

Use this checklist when reviewing a test method flagged by MethodAtlas:

- [ ] Does the test assert the outcome of the security operation (return
      value, exception, final state), not only that methods were called?
- [ ] Does the test cover the negative case (the control rejects invalid
      input or unauthorised requests)?
- [ ] Does the `@DisplayName` annotation name the security property in plain
      English?
- [ ] Does `@Tag("security")` appear on the method?
- [ ] If the interaction-only pattern is unavoidable (architecture constraint),
      is this documented in `@DisplayName` and in the override file?

## Further reading

- [AI Interaction Score](../ai/interaction-score.md) — score definition and CI extraction commands
- [Classification Overrides](../ai/overrides.md) — documenting accepted-risk decisions
- [Source Write-back](../usage-modes/apply-tags.md) — automatic annotation application
- [Apply Tags from CSV](../usage-modes/apply-tags-from-csv.md) — human-reviewed annotation write-back
- [Tag vs AI Drift](../ai/drift-detection.md) — finding unlabelled security tests
