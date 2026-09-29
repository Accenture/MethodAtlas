# Security-Domain Gap Report (`-gap-report`)

The `-gap-report` flag generates a JSON report after an AI-classified scan that lists which of the nine built-in security taxonomy domains have zero covering tests. This inverts the usual coverage question — instead of "what fraction of lines are covered?", it answers "which threat categories have no tests at all?"

## Why it exists

Standard coverage tools (JaCoCo, Istanbul, coverage.py) tell you which lines execute. They cannot tell you whether those lines test for authentication weaknesses, injection vulnerabilities, or cryptographic correctness — because they have no semantic model of test intent.

The gap report uses MethodAtlas AI classification to fill this gap. After classifying which tests cover which security domains, it reports the uncovered ones directly. An auditor reviewing a PCI-DSS or ISO 27001 evidence package can read the `gaps` array to confirm which control categories remain untested.

## Quick start

```bash
./methodatlas -ai -gap-report src/test/java
```

The report is written to `security-gap-report.json` in the current working directory.

## Options

| Flag | Effect | Default |
|------|--------|---------|
| `-gap-report` | Enable gap report generation | Off |
| `-gap-report-file <path>` | Override output path | `security-gap-report.json` in CWD |
| `-min-confidence <n>` | Exclude methods below confidence threshold | `0.0` |

## Report format

```json
{
  "schemaVersion": "1",
  "generatedUtc": "2026-09-29T12:34:56.789Z",
  "methodAtlasVersion": "3.0.0",
  "domains": {
    "auth": {
      "count": 12,
      "methods": [
        { "fqcn": "com.acme.AuthTest", "method": "testLogin", "displayName": null }
      ]
    },
    "access-control": { "count": 3, "methods": [...] },
    "crypto": { "count": 0, "methods": [] },
    "input-validation": { "count": 7, "methods": [...] },
    "injection": { "count": 0, "methods": [] },
    "data-protection": { "count": 2, "methods": [...] },
    "logging": { "count": 0, "methods": [] },
    "error-handling": { "count": 5, "methods": [...] },
    "owasp": { "count": 1, "methods": [...] }
  },
  "gaps": ["crypto", "injection", "logging"],
  "summary": {
    "totalDomains": 9,
    "coveredDomains": 6,
    "uncoveredDomains": 3,
    "coveragePercent": 66.67
  }
}
```

Fields:

- **domains** — one entry per built-in taxonomy tag; always present (count may be 0)
- **gaps** — taxonomy tags whose domain has `count == 0`; in canonical taxonomy order
- **summary.coveragePercent** — `coveredDomains / totalDomains × 100`, rounded to 2 decimal places

## The nine built-in domains

| Domain | Covers | CWE |
|--------|--------|-----|
| `auth` | Authentication tests | CWE-287 |
| `access-control` | Authorization / privilege tests | CWE-285 |
| `crypto` | Cryptography correctness tests | CWE-327 |
| `input-validation` | Input sanitisation / boundary tests | CWE-20 |
| `injection` | SQL, command, LDAP injection tests | CWE-74 |
| `data-protection` | Encryption at rest / in transit tests | CWE-311 |
| `logging` | Audit trail / log completeness tests | CWE-778 |
| `error-handling` | Sensitive data in errors, exception tests | CWE-209 |
| `owasp` | OWASP Top 10 / generic security tests | — |

## CI/CD integration

Use the `gaps` array in a release gate to block promotion when critical domains are uncovered:

```bash
#!/bin/bash
./methodatlas -ai -gap-report src/test/java

# Fail if any of these critical domains are uncovered
CRITICAL="auth injection input-validation"
GAPS=$(jq -r '.gaps[]' security-gap-report.json)
for domain in $CRITICAL; do
  if echo "$GAPS" | grep -q "^${domain}$"; then
    echo "GATE FAILED: no tests cover the '${domain}' domain"
    exit 1
  fi
done
```

See [Release gating](../ci/release-gating.md) for a complete pipeline example.

## YAML configuration

```yaml
# methodatlas.yaml
gapReport: true
gapReportFile: security-gap-report.json
minConfidence: 0.8
```
