# DORA — Digital Operational Resilience Act

Regulation (EU) 2022/2554, the *Digital Operational Resilience Act* (DORA),
entered into force in January 2023 and became applicable to financial entities
operating in the European Union from **17 January 2025**. It establishes
binding requirements for ICT risk management, incident reporting, digital
operational resilience testing, and third-party ICT risk oversight.

DORA applies to a broad range of financial entities, including credit
institutions, payment institutions, investment firms, insurance undertakings,
crypto-asset service providers, and ICT third-party service providers
designated as critical by the competent supervisory authorities.

## Relevant article

**Article 25 — Testing of ICT tools and systems** requires covered entities
to establish, maintain, and review a sound and comprehensive digital
operational resilience testing programme as an integral part of their ICT
risk-management framework. The programme must:

- Cover a broad range of assessments, tests, methodologies, and tools.
- Be applied on a regular, documented basis.
- Produce results that support supervisory review.

Article 25 further specifies that all relevant ICT systems and applications
supporting critical or important functions shall be tested at least annually.
Results are to be documented and available to competent authorities upon
request.

DORA additionally requires that detection mechanisms related to anomalous
activities and ICT-related incidents are regularly tested (Article 25(3)(d)).
For software teams in scope, security regression tests — which verify that
detection and prevention mechanisms continue to function correctly — fall
within this scope.

## Control mapping

| DORA Article 25 requirement                                                      | MethodAtlas feature                                                                           | Evidence produced |
|----------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|-------------------|
| Sound and comprehensive resilience testing programme                             | Structured inventory of security tests, classified by category, run on a documented schedule  | `security-tests.sarif` per release; `ai_tags` categorising tests by `auth`, `session`, `anomaly`, and other control types |
| Applied on a regular, documented basis                                           | CI integration produces a timestamped artefact on every push to `main` or on a scheduled pipeline | [`-emit-metadata`](../cli-reference.md#-emit-metadata) prepends scan timestamp and tool version to each output |
| Results available to competent authorities                                       | SARIF 2.1.0 is a standardised, tool-importable format; retained alongside build artefacts    | `security-tests.sarif` stored in CI artifact store or release assets |
| Detection mechanisms regularly tested (Art. 25(3)(d))                           | Tests in the `auth`, `session`, and `anomaly` taxonomy categories are identified and counted  | `ai_tags` column filterable by category in CSV output |
| ICT systems supporting critical functions tested at least annually               | Scheduled CI scan with retained output satisfies the annual documentation requirement         | Timestamped SARIF artefacts with commit SHA in filename |
| Coverage has not regressed between assessment periods                            | [`-diff`](../cli-reference.md#-diff) mode flags removed or reclassified security tests        | `delta.csv` listing all changes; summary line shows security-relevant count delta |

## Recommended configuration

**Context:** Article 25 requires a timestamped, per-release record that can be
presented to competent authorities on request. The combination of date and
commit SHA in the output file name produces an unambiguous archive key.

**MethodAtlas capability:** [`-emit-metadata`](../cli-reference.md#-emit-metadata),
[`-content-hash`](../cli-reference.md#-content-hash), and `-sarif` together
produce an artefact suitable for supervisory submission.

```bash
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -sarif \
  -security-only \
  -content-hash \
  -emit-metadata \
  src/test/java \
  > security-tests-$(date +%Y%m%d)-$(git rev-parse --short HEAD).sarif
```

**Evidence output:** `security-tests-YYYYMMDD-<sha>.sarif` — a SARIF file
suitable for archiving and supervisory presentation, with embedded metadata
linking the scan to a specific date and source revision.

## Regression testing programme

Article 25 requires that the testing programme addresses *whether existing
controls remain effective* — a requirement that extends beyond confirming
new tests exist to confirming that existing tests have not been degraded or
removed.

MethodAtlas produces evidence for this requirement directly:

```bash
# Produce the baseline at the start of the review period
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash -emit-metadata \
  src/test/java > baseline.csv

# At release time, compare against the baseline
java -jar methodatlas.jar -diff baseline.csv current.csv > delta.csv
```

A non-empty `delta.csv` containing removed or reclassified security test
methods warrants review before the release is signed off under the testing
programme.

## Artefact retention

DORA does not define a single universal retention period; the applicable
period for your entity is determined by sector-specific regulatory technical
standards (RTS) issued by the European Supervisory Authorities (ESAs).
As a baseline:

| Artefact                      | Minimum retention guidance |
|-------------------------------|----------------------------|
| SARIF output per release      | 5 years (align with ICT incident record requirements) |
| Baseline and delta CSVs       | 5 years |
| CI pipeline logs with commit SHA | 5 years |

!!! warning "Retention period"
    Confirm the applicable retention period with your compliance officer
    or legal counsel. Sector-specific RTS may impose different requirements.

## Air-gapped and restricted environments

Financial entities operating infrastructure that prohibits outbound API calls
can use the [Manual AI workflow](../usage-modes/manual.md) to perform AI
classification without network calls from the scan host. The workflow
separates the scanning phase (which produces prompt files) from the
classification phase (which can be performed on a separately authorised
workstation).

## Further reading

- [DORA — Regulation (EU) 2022/2554 (Official Journal)](https://eur-lex.europa.eu/eli/reg/2022/2554/oj/eng)
- [European Banking Authority — DORA technical standards](https://www.eba.europa.eu/regulation-and-policy/operational-resilience/digital-operational-resilience-act-dora)
- [European Insurance and Occupational Pensions Authority — DORA guidance](https://www.eiopa.europa.eu/digital-operational-resilience-act_en)
- [MethodAtlas — Manual AI Workflow](../usage-modes/manual.md)
- [MethodAtlas — Delta Report](../usage-modes/delta.md)
