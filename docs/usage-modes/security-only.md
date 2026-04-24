# Security-Only Output Filter

The `-security-only` flag restricts the output of a scan to methods that were
classified as security-relevant. All other test methods are silently dropped
before output is written, whether the format is CSV, plain text, or SARIF.

## Basic usage

```bash
./methodatlas -ai -security-only src/test/java
```

```bash
# SARIF for GitHub Code Scanning — only security findings
./methodatlas -ai -sarif -security-only src/test/java > security-tests.sarif
```

## Why this filter exists

A typical Java project has hundreds or thousands of JUnit tests. Only a small
fraction of them explicitly verify security properties. Without filtering, a full
MethodAtlas scan emits one row per test method — most of which carry
`ai_security_relevant=false` and add noise to any downstream consumer.

The `-security-only` filter removes that noise at the source:

| Consumer | Problem without filter | With `-security-only` |
|---|---|---|
| **GitHub Code Scanning** | Thousands of `level: none` SARIF findings flood the Security tab and bury real results | Only security-relevant tests appear as `level: note` findings |
| **Auditor CSV** | Reviewer must manually skip hundreds of irrelevant rows | Compact list of exactly the tests that cover security requirements |
| **CI security gate** | `wc -l` on the CSV includes headers and non-security rows | Direct row count equals number of security-relevant tests |
| **Team dashboard** | Charts dominated by non-security test volume | Metrics reflect only the security-test layer |

## What "security-relevant" means

A method is included in the output when its `ai_security_relevant` field is
`true`. This value is set by:

1. **AI classification** — the model determined the method tests a named
   security property (authentication, cryptography, input validation, etc.)
2. **Human override** — an [`-override-file`](../ai/overrides.md) entry
   explicitly sets `securityRelevant: true` for the method

A method with no AI suggestion (class skipped due to size limit, AI unavailable,
or AI disabled) is treated as `securityRelevant=false` and is dropped.

## Requiring AI or overrides

`-security-only` has no effect without a source of security classifications.
If neither `-ai` nor `-override-file` is configured, no method has a
`securityRelevant=true` value and the output will be empty. Always combine
the filter with at least one of:

```bash
# With live AI
./methodatlas -ai -security-only src/test/java

# With manual AI workflow (consume phase)
./methodatlas -manual-consume ./work ./responses -security-only src/test/java

# With override file only (static, no AI)
./methodatlas -override-file .methodatlas-overrides.yaml -security-only src/test/java
```

## Configuration file

The filter can be enabled via the YAML configuration file so that every run in
a project uses it by default:

```yaml
securityOnly: true

ai:
  enabled: true
  provider: anthropic
  model: claude-sonnet-4-5
```

A command-line `-security-only` flag always overrides the YAML setting.

## SDLC integration examples

### GitHub Code Scanning — clean Security tab

Without `-security-only`, every test method produces a SARIF result. Most have
`level: none` and contribute no actionable information but fill the Security tab
with hundreds of entries.

```yaml
# .github/workflows/security-scan.yml (excerpt)
- name: MethodAtlas security scan
  run: |
    ./methodatlas \
      -ai -sarif -security-only \
      -ai-provider anthropic \
      -ai-api-key-env ANTHROPIC_API_KEY \
      src/test/java > security-tests.sarif

- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: security-tests.sarif
```

The uploaded SARIF will contain only security-relevant test methods, each
appearing as an annotation on the corresponding source line in the PR diff.

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
AI rationale included, for submission to an auditor or a compliance review:

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

The resulting delta report will show only security-test changes — free of noise
from unrelated functional tests.

See [CLI reference — `-security-only`](../cli-reference.md#-security-only) for
the flag description.
