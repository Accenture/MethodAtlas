# PCI-DSS v4.0

The Payment Card Industry Data Security Standard (PCI-DSS), published by
the [PCI Security Standards Council](https://www.pcisecuritystandards.org/),
applies to all entities that store, process, or transmit cardholder data.
Version 4.0 became mandatory in April 2024, replacing version 3.2.1.

!!! note "Applicability assessment"
    Whether PCI-DSS applies to your organisation, and which requirements are
    in scope for your specific cardholder data environment, must be determined
    by a Qualified Security Assessor (QSA) or an Internal Security Assessor
    (ISA). This page provides technical guidance for teams whose QSA has
    confirmed applicability.

## Relevant requirements

**Requirement 6 — Develop and Maintain Secure Systems and Software**
establishes that all bespoke and custom software must be developed according
to a documented secure development life cycle. The requirement covers
security design review, threat modelling, secure coding practices, and
security testing throughout the development process, including before
software is deployed to production.

Among the verification activities QSAs typically review are:

- Evidence that security testing is performed as part of the SDLC.
- Records showing which security tests exist, what they cover, and when
  they were last executed.
- Artefacts that allow a specific test result to be correlated with a
  specific source revision.

## Control mapping

| PCI-DSS v4.0 control              | MethodAtlas feature                                                      | Evidence produced |
|-----------------------------------|--------------------------------------------------------------------------|-------------------|
| 6.2.4 — Software development practices prevent common vulnerabilities | AI taxonomy tags identify tests covering injection, auth, crypto, and access-control requirements | `ai_tags` column in CSV/SARIF listing security categories covered per test method |
| 6.3.2 — Inventory of bespoke and custom software | Full structural inventory of test methods with FQCNs, line counts, and tags | `inventory.csv` produced by static scan |
| 6.4.1 — Security testing is performed before software is released to production | CI gate runs MethodAtlas on every release candidate; SARIF output proves the scan ran | `security-tests.sarif` artifact attached to CI build log |
| 6.4.2 — Internal vulnerability scan after significant change | Delta report flags removed or reclassified security tests between releases | `delta-report.csv` produced by `-diff` mode |
| 6.4.3 — Web-application protection using a technical control | Tests in the `injection` and `input-validation` taxonomy categories demonstrate coverage of web-application controls | `ai_tags` values in CSV filterable by category |

## Recommended configuration

**Context:** PCI-DSS Requirement 6.4.1 requires that security testing evidence
can be correlated with the release. The following command embeds the metadata
needed for that correlation.

**MethodAtlas capability:** [`-content-hash`](../cli-reference.md#-content-hash)
and [`-emit-metadata`](../cli-reference.md#-emit-metadata) produce a
tamper-evident record tied to a specific source revision.

```bash
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -sarif \
  -security-only \
  -content-hash \
  -emit-metadata \
  src/test/java \
  > security-tests.sarif
```

Run this command on every release candidate build and retain the output
alongside the build artefacts.

For scheduled evidence runs (weekly or monthly), also produce a CSV alongside
the SARIF:

```bash
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash \
  -emit-metadata \
  src/test/java \
  > security-tests.csv
```

**Evidence output:** the CSV format is more readable for human review; the
SARIF format integrates with code scanning tools and can be attached to
assessment submissions.

## Regression detection

PCI-DSS assessors may ask for evidence that security testing coverage did not
decrease between release cycles. The `-diff` mode compares two scan outputs:

```bash
java -jar methodatlas.jar \
  -diff baseline.csv current.csv \
  > delta-report.csv
```

A non-empty delta report warrants review before the release proceeds. The
delta highlights removed test methods, reclassified methods, and methods
whose interaction score worsened.

## Artefact package

For each release or quarterly review period, retain the following artefacts:

| Artefact              | Source                                      | Minimum retention |
|-----------------------|---------------------------------------------|-------------------|
| `security-tests.sarif`| MethodAtlas SARIF output                    | 1 year (align with QSA assessment cycle) |
| `security-tests.csv`  | MethodAtlas CSV output with `-emit-metadata` | 1 year |
| `delta-report.csv`    | MethodAtlas `-diff` output                  | 1 year |
| CI build log with commit SHA | CI platform                          | 1 year |

!!! warning "Retention period"
    PCI-DSS does not specify a universal artefact retention period; your QSA
    determines the period appropriate to your assessment cycle. The one-year
    guidance above reflects a common baseline; consult your QSA.

## Checklist for QSA presentation

The following items are typically requested during a PCI-DSS assessment for
software security testing:

- [ ] Security testing is performed as part of the SDLC (evidence: CI pipeline
      logs showing MethodAtlas execution on each release candidate).
- [ ] Test coverage is traceable to specific source revisions (evidence:
      `content_hash` column in CSV/SARIF correlated with git commit SHA).
- [ ] A documented taxonomy is applied to classify security tests
      (evidence: MethodAtlas taxonomy file or built-in taxonomy documentation).
- [ ] Test coverage did not regress between releases (evidence: delta report
      showing no removed security-relevant test methods).
- [ ] AI classification rationale is available for review (evidence:
      `ai_reason` column in CSV output).

## Further reading

- [PCI Security Standards Council — Document Library](https://www.pcisecuritystandards.org/document_library/)
- [PCI-DSS v4.0 — Requirement 6 summary (PCI SSC)](https://www.pcisecuritystandards.org/faq/)
- [MethodAtlas — Output Formats](../output-formats.md)
- [MethodAtlas — Delta Report](../usage-modes/delta.md)
