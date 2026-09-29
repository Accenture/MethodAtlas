# Regulated Environment Deployment Guide

This guide is for teams operating under a formal compliance programme —
PCI-DSS, ISO 27001, SOC 2 Type II, HIPAA, or NIST SSDF — who need to
integrate MethodAtlas into their audit evidence workflow. It covers the
minimum flag set required by each standard, artifact retention expectations,
and how to present MethodAtlas output to internal or external auditors.

For standard-specific deep dives see the pages under
[Deployment](../deployment/).

## Why MethodAtlas matters in regulated environments

Regulatory frameworks increasingly require evidence that security controls
are not merely implemented but continuously tested. MethodAtlas produces
machine-readable, auditable evidence of security test coverage in a format
that complements SAST findings and pen-test reports.

| What auditors typically ask | What MethodAtlas produces |
|-----------------------------|---------------------------|
| Do you test your authentication controls? | `auth` tag count with method names and confidence scores |
| How many security tests do you have, and did the count drop since last release? | `-diff` delta report with `-require-justification` gate |
| Can you prove the tests were unchanged at release time? | Content hash column (`-content-hash`) + reproducibility receipt (`-emit-receipt`) |
| Which CWEs does your test suite address? | `ai_cwe` column (`-ai-cwe`) |
| Are there security control areas with zero test coverage? | Gap report (`-gap-report`) |

## Minimum flag set per standard

The table below lists the flags that provide the most direct evidence value
for each standard. All standards benefit from the baseline set; the
additional columns indicate what each standard specifically rewards.

| Flag | PCI-DSS v4 | ISO 27001 | SOC 2 | HIPAA | NIST SSDF |
|------|-----------|-----------|-------|-------|-----------|
| `-ai` | Required | Required | Required | Required | Required |
| `-content-hash` | Required | Required | Required | Required | Required |
| `-ai-cache` | Recommended | Recommended | Recommended | Recommended | Recommended |
| `-security-only` | Recommended | Recommended | Recommended | Recommended | Recommended |
| `-ai-cwe` | Required (Req 6.2.4) | Recommended | Recommended | Recommended | Required |
| `-gap-report` | Required | Recommended | Recommended | Recommended | Recommended |
| `-emit-receipt` | Required | Required | Required | Required | Required |
| `-require-justification` | Required | Required | Required | Recommended | Recommended |
| `-override-file` | Required | Required | Required | Required | Required |

### PCI-DSS v4.0 — Requirement 6.2.4

PCI-DSS Requirement 6.2.4 requires that all software development personnel
are trained on secure coding practices and that the software development
lifecycle includes security testing. MethodAtlas provides:

- A timestamped inventory of security-focused test methods (`-ai`)
- CWE coverage evidence (`-ai-cwe`) — Requirement 6.2.4 references the CWE
  standard as the control taxonomy
- Proof that security tests were not removed without justification
  (`-require-justification`)
- A content-hash fingerprint of the test suite at release time (`-content-hash -emit-receipt`)

Minimum invocation for a PCI-DSS evidence run:

```bash
./bin/methodatlas \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash \
  -ai-cwe \
  -security-only \
  -gap-report -gap-report-file pci-gap-report.json \
  -emit-receipt \
  -override-file .methodatlas-overrides.yaml \
  src/test/java > pci-security-tests.csv
```

See [docs/deployment/pci-dss.md](../deployment/pci-dss.md) for a full
control-by-control mapping.

### ISO 27001:2022 — Annex A 8.29

ISO 27001 Annex A control 8.29 (Security testing in development and
acceptance) requires that security testing is carried out as part of the
development process. MethodAtlas output contributes to the Statement of
Applicability (SoA) evidence package.

Minimum invocation:

```bash
./bin/methodatlas \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash \
  -security-only \
  -emit-receipt \
  -override-file .methodatlas-overrides.yaml \
  src/test/java > iso27001-security-tests.csv
```

See [docs/deployment/iso-27001.md](../deployment/iso-27001.md).

### SOC 2 Type II — CC8.1

SOC 2 CC8.1 (Change management) requires that changes to production systems
go through a formal review process. MethodAtlas contributes evidence that
the change review included verification of the security test suite.

Minimum invocation:

```bash
./bin/methodatlas \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash \
  -security-only \
  -emit-receipt \
  src/test/java > soc2-security-tests.csv

# Gate: no security tests removed without justification
./bin/methodatlas \
  -diff previous-release.csv soc2-security-tests.csv \
  -require-justification \
  -override-file .methodatlas-overrides.yaml
```

See [docs/deployment/soc2.md](../deployment/soc2.md).

### HIPAA §164.312 (Technical Safeguards)

HIPAA requires technical safeguards including access controls, audit controls,
integrity controls, and transmission security. MethodAtlas identifies tests
covering these safeguards by taxonomy tag and produces evidence that they
are tested continuously.

Minimum invocation:

```bash
./bin/methodatlas \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash \
  -security-only \
  -gap-report -gap-report-file hipaa-gap-report.json \
  src/test/java > hipaa-security-tests.csv
```

HIPAA auditors are particularly interested in the `auth`, `access-control`,
`data-protection`, and `crypto` taxonomy domains. Review the gap report
to confirm all four domains have covering tests before a risk assessment.

## Artifact retention

| Artifact | Suggested retention | Notes |
|----------|--------------------:|-------|
| Scan CSV (`security-tests.csv`) | 7 years | Primary evidence artifact |
| SARIF output | 3 years | Code Scanning history |
| Gap report (`security-gap-report.json`) | 7 years | Coverage evidence |
| Override file (`.methodatlas-overrides.yaml`) | Permanent (VCS) | Human decision audit trail |
| Reproducibility receipt (`methodatlas-receipt.json`) | 7 years | Replay evidence |
| Delta report (`-diff` output) | 3 years | Change-control evidence |

Store CSV and JSON artifacts in an immutable object store (S3 with object
lock, Azure Blob Storage with immutability policy, or equivalent) with
encryption at rest. The override file is stored in version control — its
Git history is the audit trail and must be retained with the repository.

## Presenting output to auditors

### Internal auditors

Internal auditors typically want to see:

1. The CSV or SARIF output demonstrating the security test inventory
2. The gap report confirming all relevant taxonomy domains have coverage
3. The delta report or CI gate logs proving no tests were silently removed
4. The override file showing human-reviewed decisions with reviewer names
   and dates in the `note` field

Prepare a one-page summary from the [security teams guide](../concepts/for-security-teams.md#executive-summary-template) and attach the CSV and gap report as appendices.

### External auditors (QSA, ISO CB, SOC 2 auditor)

External auditors require evidence that is:

- **Timestamped** — use `-emit-metadata` to embed the scan timestamp in CSV output and `-emit-receipt` to produce a cryptographic integrity record
- **Reproducible** — the reproducibility receipt (`methodatlas-receipt.json`) records the SHA-256 of every input; the auditor can request a re-run and compare
- **Version-controlled** — commit the override file to source control; its history is the change-control record

When presenting the SARIF to a QSA or ISO CB, filter to security-relevant
findings only (`-security-only`) to keep the report focused on in-scope controls.

## CI/CD integration pattern for regulated environments

The following pattern is recommended for regulated pipelines. It separates
the AI classification pass from the gate pass so that a CI cache failure
does not block the gate:

```bash
# Step 1: classify (with cache)
./bin/methodatlas \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash \
  -ai-cwe \
  -security-only \
  -ai-cache .methodatlas-cache.json \
  -ai-cache-out .methodatlas-cache.json \
  -emit-receipt \
  -override-file .methodatlas-overrides.yaml \
  -gap-report -gap-report-file security-gap-report.json \
  src/test/java > current.csv

# Step 2: gate (no AI calls, pure diff)
./bin/methodatlas \
  -diff baseline.csv current.csv \
  -require-justification \
  -override-file .methodatlas-overrides.yaml
```

Publish `current.csv`, `security-gap-report.json`, and
`methodatlas-receipt.json` as immutable CI artifacts. Update `baseline.csv`
only on successful pushes to the default branch.

## Further reading

- [PCI-DSS deployment](../deployment/pci-dss.md)
- [ISO 27001 deployment](../deployment/iso-27001.md)
- [SOC 2 deployment](../deployment/soc2.md)
- [NIST SSDF deployment](../deployment/nist-ssdf.md)
- [Release gating and regression prevention](../ci/release-gating.md)
- [Classification overrides](../ai/overrides.md)
- [Reproducibility receipts](../usage-modes/reproducibility-receipts.md)
- [Security teams guide](../concepts/for-security-teams.md)
