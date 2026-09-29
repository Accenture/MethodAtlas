# Security Evidence Report (`-evidence-report`)

The `-evidence-report` flag generates a human-readable Markdown document after an AI-classified scan. The report lists every security-relevant test method found in the scan, grouped by taxonomy domain, with full AI classification details — suitable for attachment to a compliance evidence package or auditor review.

## Why it exists

JaCoCo reports what percentage of lines are covered. SAST tools report what vulnerabilities are present in production code. Neither answers: *"Which security properties is this codebase testing for, and how thoroughly?"*

The evidence report fills this gap. It is the only artifact that connects individual test methods to security taxonomy domains (CWE categories, ASVS controls, ISO 27001 Annex A requirements) with human-readable context from the AI classification.

## Quick start

```bash
./methodatlas -ai -evidence-report src/test/java
```

The report is written to `security-evidence-report.md` in the current working directory.

## Options

| Flag | Effect | Default |
|------|--------|---------|
| `-evidence-report` | Enable evidence report generation | Off |
| `-evidence-report-file <path>` | Override output path | `security-evidence-report.md` in CWD |
| `-min-confidence <n>` | Exclude methods whose AI confidence is below `n` (0.0–1.0) | `0.0` |

## Report structure

```
# Security Evidence Report

Generated: 2026-09-29T12:34:56.789Z
MethodAtlas version: 3.0.0
Scanned roots: src/test/java
Total methods scanned: 1 234
Security-relevant methods: 87

---

## auth (12 methods)

### com.acme.AuthTest

#### testLogin_validCredentials

| Field | Value |
|-------|-------|
| AI tags | auth, access-control |
| AI security relevant | true |
| AI confidence | 98 % |
| Interaction score | 1.0 |
| CWE | CWE-287, CWE-285 |
| Content hash | a1b2c3… |
| Lines | 45–60 (16 loc) |
```

Each method section contains:

- **AI tags** — taxonomy domains from the AI classification
- **AI security relevant** — `true` or `false`
- **AI confidence** — percentage (0–100 %)
- **Interaction score** — 0.0 (no assertion) to 1.0 (rich assertion-based test)
- **CWE** — Common Weakness Enumeration identifiers mapped from AI tags
- **Content hash** — SHA-256 of the enclosing class source (present when `-content-hash` was used)
- **Lines** — first line and lines-of-code for the method declaration

## Filtering by confidence

Use `-min-confidence` to exclude uncertain classifications from the report:

```bash
./methodatlas -ai -evidence-report -min-confidence 0.8 src/test/java
```

Only methods whose AI confidence is 0.8 (80 %) or higher appear in the report.

## CI/CD integration

Publish the report as a workflow artifact so it is accessible from the Actions run:

```yaml
# GitHub Actions
- name: Scan and emit evidence report
  run: |
    ./methodatlas -ai -evidence-report src/test/java

- name: Upload evidence report
  uses: actions/upload-artifact@v4
  with:
    name: security-evidence-report
    path: security-evidence-report.md
    retention-days: 90
```

For audit purposes, store the report for as long as your compliance framework requires (PCI-DSS: 1 year; ISO 27001: per your ISMS policy; SOC 2: typically 1 year).

## Combining with the attestation manifest

For a complete compliance package, generate both the evidence report (human-readable detail) and the attestation manifest (machine-verifiable, signable summary):

```bash
./methodatlas -ai -evidence-report -attest \
  -evidence-report-file evidence-report.md \
  -attest-file security-attestation.json \
  src/test/java
```

See [Attestation manifest](attest.md) for signing instructions.

## YAML configuration

```yaml
# methodatlas.yaml
evidenceReport: true
evidenceReportFile: security-evidence-report.md
minConfidence: 0.8
```
