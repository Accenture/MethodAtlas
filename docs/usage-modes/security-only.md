# Security-Only Output Filter

The security-only filter restricts output to methods classified as security-relevant, dropping all other test methods before any output is written.

## When to use this mode

- You are producing a CSV for an auditor and want it to contain only the tests that are relevant to security — not the full test suite.
- You are uploading SARIF to GitHub Code Scanning and want the Security tab to show only security-relevant findings, not every test method.
- You are running a CI gate that checks the count of security-relevant tests and want a focused output to count from.
- You are combining with the [delta report](delta.md) to produce change reports that focus purely on the security-test layer.

Note: the filter requires a source of security classifications — either [`-ai`](../cli-reference.md#-ai), the [manual workflow](manual.md) consume phase, or an [`-override-file`](../cli-reference.md#-override-file). Without one of these, the output will be empty.

## SARIF mode: security-only by default

When SARIF output is selected (`-sarif` or `outputMode: sarif` in YAML), the
security-only filter is **applied automatically**. SARIF is consumed by GitHub
Code Scanning and equivalent security tooling that expects actionable findings —
not an exhaustive inventory of every test method. Emitting hundreds of
`level: none` entries for ordinary unit tests floods the Security tab with noise
that cannot be bulk-dismissed and makes the tool unusable in practice.

```bash
# Security findings only — the default for SARIF
./methodatlas -ai -sarif src/test/java > security-tests.sarif
```

To include all test methods in the SARIF document (for example, to feed a
custom analysis pipeline), pass `-include-non-security`:

```bash
# Full inventory SARIF — opt-in
./methodatlas -ai -sarif -include-non-security src/test/java > all-tests.sarif
```

The same opt-in is available in YAML configuration:

```yaml
outputMode: sarif
includeNonSecurity: true
```

## CSV and plain-text modes: opt-in filter

For CSV and plain-text output, the filter is **not applied by default** — every
discovered test method is emitted. Use `-security-only` to restrict the output
when only security-relevant rows are needed:

```bash
# All methods (default)
./methodatlas -ai src/test/java

# Security methods only
./methodatlas -ai -security-only src/test/java
```

The flag can also be enabled via YAML:

```yaml
securityOnly: true
ai:
  enabled: true
  provider: anthropic
  model: claude-sonnet-4-5
```

## What "security-relevant" means

A method is included when its `ai_security_relevant` field is `true`. This value
is set by:

1. **AI classification** — the model determined the method tests a named security
   property (authentication, cryptography, input validation, etc.)
2. **Human override** — an [`-override-file`](../ai/overrides.md) entry
   explicitly sets `securityRelevant: true` for the method

A method with no AI suggestion (class too large, AI unavailable, or AI disabled)
is treated as `securityRelevant=false` and is dropped.

## Requiring AI or overrides

The security-only filter has no effect without a source of security
classifications. If neither `-ai` nor `-override-file` is configured, no method
has a `securityRelevant=true` value and the output will be empty. Always combine
the filter with at least one of:

```bash
# With live AI
./methodatlas -ai -security-only src/test/java

# With manual AI workflow (consume phase)
./methodatlas -manual-consume ./work ./responses -security-only src/test/java

# With override file only (static, no AI)
./methodatlas -override-file .methodatlas-overrides.yaml -security-only src/test/java
```

This requirement applies equally to SARIF mode: without AI or an override file,
the SARIF document will contain zero results.

## SDLC integration examples

### GitHub Code Scanning — clean Security tab

```yaml
# .github/workflows/security-scan.yml (excerpt)
- name: MethodAtlas security scan
  run: |
    ./methodatlas \
      -ai -sarif \
      -ai-provider anthropic \
      -ai-api-key-env ANTHROPIC_API_KEY \
      src/test/java > security-tests.sarif

- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: security-tests.sarif
```

The uploaded SARIF contains only security-relevant test methods. Each finding
carries a `security-severity` score (derived from the AI taxonomy tag) that
GitHub renders as Critical, High, Medium, or Low — making it straightforward to
prioritise the security manager's backlog.

### CI gate — minimum security-test count

Fail the build if fewer than a threshold number of security tests are found:

```bash
./methodatlas -ai -security-only \
  -ai-provider anthropic \
  -ai-api-key-env ANTHROPIC_API_KEY \
  src/test/java > security-tests.csv

# Count data rows (subtract 1 for the header)
COUNT=$(( $(wc -l < security-tests.csv) - 1 ))
MIN=10

if [ "$COUNT" -lt "$MIN" ]; then
  echo "ERROR: only $COUNT security-relevant tests found (minimum: $MIN)"
  exit 1
fi
echo "OK: $COUNT security-relevant tests"
```

### Audit export — evidence for a security review

Produce a clean CSV listing every test that verifies a security property, with
AI rationale included, for submission to an auditor or compliance review:

```bash
./methodatlas \
  -ai -security-only -emit-metadata \
  -override-file .methodatlas-overrides.yaml \
  -ai-provider anthropic \
  -ai-api-key-env ANTHROPIC_API_KEY \
  src/test/java > security-test-inventory-$(date +%F).csv
```

The `ai_reason` column documents *why* each method was classified as
security-relevant, making the classification defensible in a review.

### Delta report on security tests only

Combine `-security-only` with the [delta report](delta.md) workflow to produce
change reports that focus purely on the security-test layer:

```bash
# Produce security-only snapshots at two points in time
./methodatlas -ai -security-only -content-hash -emit-metadata \
  src/test/java > security-before.csv

# ... after changes ...

./methodatlas -ai -security-only -content-hash -emit-metadata \
  src/test/java > security-after.csv

# Compare
./methodatlas -diff security-before.csv security-after.csv
```

The resulting delta report shows only security-test changes — free of noise from
unrelated functional tests.

See [CLI reference — `-security-only`](../cli-reference.md#-security-only) for
the flag description and [`-include-non-security`](../cli-reference.md#-include-non-security)
for the SARIF full-inventory opt-in.
