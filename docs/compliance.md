# Compliance and Standards

Several widely adopted frameworks explicitly require evidence of security testing
as part of the development and assurance process. This page summarises what each
framework demands, maps specific control identifiers to MethodAtlas features, and
describes the evidence each feature produces.

!!! note "Applicability varies by sector and organisation"
    The frameworks listed here are referenced for informational purposes.
    Determining which apply to your organisation, and precisely how they must
    be satisfied, requires assessment by a qualified compliance professional.
    Do not rely solely on this page as compliance guidance.

!!! info "Data scope for external AI providers"
    When an external AI provider is configured, MethodAtlas submits only the
    test class source file — not production source code, configuration files,
    or any other project content. This limits the data governance review
    required before approving external API use to a single artefact category:
    test source files. For environments where even this is not permitted, the
    [Manual AI workflow](usage-modes/manual.md) performs classification
    without any outbound network calls from the scan host.

## OWASP SAMM v2 — Security Testing

The [OWASP Software Assurance Maturity Model (SAMM) v2](https://owaspsamm.org/model/verification/security-testing/)
defines a **Security Testing** practice under its *Verification* business function.
The practice is divided into two streams:

**Stream A — Scalable Baseline** focuses on establishing automated security tests
that run consistently across all applications. At maturity level 1, teams are
expected to apply automated security testing tools and record results. At maturity
level 2, the baseline is customised to the technology stack and risk profile of
each application, with tool tuning to minimise false positives.

**Stream B — Deep Understanding** complements automated tests with expert manual
analysis focused on complex attack vectors.

| OWASP SAMM v2 activity                   | MethodAtlas feature                                                                           | Evidence produced |
|------------------------------------------|-----------------------------------------------------------------------------------------------|-------------------|
| ST-A-1: Automated security testing tools applied consistently | CI integration runs MethodAtlas on every push; `-security-only` filters to relevant findings | Gate job status in CI build log per commit |
| ST-A-1: Results recorded                 | SARIF and CSV outputs archived as CI artefacts                                               | `security-tests.sarif` / `.csv` per build |
| ST-A-2: Tool tuned to technology stack and risk profile | Custom taxonomy via [`-ai-taxonomy`](cli-reference.md#-ai-taxonomy); override file eliminates known false positives | Taxonomy file; [`-override-file`](cli-reference.md#-override-file) git history |
| ST-A-2: Coverage tracked over time       | `-diff` mode produces a delta report between any two scan outputs                            | `delta.csv` / `delta.txt` per release |
| ST-B-1: Manual expert analysis documented | [`-override-file`](cli-reference.md#-override-file) records human classification decisions with rationale | Override file diff in version control |

MethodAtlas provides evidence of Stream A maturity level 1 through its automated
classification and CI integration, and evidence of Stream A maturity level 2
through taxonomy customisation and the override file mechanism.

## NIST SP 800-218 (SSDF) — Practice PW.8

The [NIST Secure Software Development Framework (SSDF)](https://csrc.nist.gov/pubs/sp/800/218/final),
Special Publication 800-218, defines practice **PW.8: "Test Executable Code to
Identify Vulnerabilities and Verify Compliance with Security Requirements"**
within the *Produce Well-Secured Software* category.

PW.8 tasks include executing security-focused tests, reviewing and analysing
results, and remediating identified vulnerabilities before release. The framework
requires that organisations demonstrate which tests address which security
requirements.

| SSDF task                                                         | MethodAtlas feature                                                                      | Evidence produced |
|-------------------------------------------------------------------|------------------------------------------------------------------------------------------|-------------------|
| PW.8.1 — Test using techniques appropriate to risk                | AI taxonomy assigns tags tied to risk categories (`auth`, `crypto`, `injection`, `session`) | `ai_tags` column in CSV/SARIF |
| PW.8.1 — Review results                                           | `ai_reason` column provides human-readable rationale for each classification decision    | `ai_reason` values in CSV output |
| PW.8.1 — Document testing approach                                | [`-emit-metadata`](cli-reference.md#-emit-metadata) prepends scan timestamp and tool version | Metadata comment block at top of CSV output |
| PW.8.2 — Verify security controls and document results            | SARIF output provides a machine-readable, timestamped record of which test methods cover which controls | `security-tests.sarif` per release |
| PW.8.2 — Trace tests to security requirements                     | Taxonomy tags provide a structured mapping from test method to requirement category      | `ai_tags` column; custom taxonomy via [`-ai-taxonomy`](cli-reference.md#-ai-taxonomy) |
| PW.8.2 — Tie results to specific source revision                  | [`-content-hash`](cli-reference.md#-content-hash) SHA-256 fingerprint per class         | `content_hash` column correlatable with git commit SHA |

MethodAtlas provides evidence that satisfies PW.8.1's review and documentation
requirements through its `ai_reason` column and `-emit-metadata` output, and
PW.8.2's traceability requirements through `ai_tags` and `-content-hash`.

See [NIST SSDF deployment guide](deployment/nist-ssdf.md) for configuration
details and the full artefact package.

## ISO/IEC 27001:2022 — Annex A Control 8.29

[ISO/IEC 27001:2022](https://www.iso.org/standard/27001) Annex A Control
**8.29 — Security Testing in Development and Acceptance** mandates that
organisations define and implement security testing processes throughout the
software development life cycle and before systems are accepted into production.

According to publicly available guidance on this control, auditors assess
conformance by looking for a documented security testing plan with defined
acceptance criteria, test results traceable to specific security requirements,
and evidence that releases were blocked when security tests failed.

| Control 8.29 objective                                     | MethodAtlas feature                                                                      | Evidence produced |
|------------------------------------------------------------|------------------------------------------------------------------------------------------|-------------------|
| Documented security testing plan with acceptance criteria  | Baseline CSV from `main` branch; release gate blocks merges when count regresses         | `baseline.csv` as release artefact; gate job failure in CI logs |
| Test results traceable to security requirements            | `ai_tags` column maps each test to a security control category                           | `ai_tags` values (e.g. `auth`, `crypto`, `injection`) in every CSV/SARIF row |
| Traceability to source revision                            | [`-content-hash`](cli-reference.md#-content-hash) SHA-256 fingerprint per class         | `content_hash` column in CSV/SARIF; correlate with git commit SHA |
| Repeated testing across SDLC stages                        | CI integration produces a timestamped artefact on every push to `main` and on release builds | Timestamped SARIF artefacts per build in CI artifact store |
| Human override and review trail                            | [`-override-file`](cli-reference.md#-override-file) records manual decisions with rationale | Override file diff in version control; `override_applied` column in CSV |

MethodAtlas provides evidence of traceability (Control 8.29 items 1–3) through
`-content-hash` and `ai_tags`, and evidence of repeatable, human-reviewed testing
(items 4–5) through CI integration and the override file.

See [ISO/IEC 27001 deployment guide](deployment/iso-27001.md) for the
recommended configuration and Statement of Applicability template.

## DORA (EU 2022/2554) — Article 25

The [Digital Operational Resilience Act (DORA)](https://eur-lex.europa.eu/eli/reg/2022/2554/oj/eng),
which applies to financial entities operating in the EU, requires in
**Article 25** that covered entities establish, maintain, and review a sound and
comprehensive digital operational resilience testing programme as an integral part
of their ICT risk-management framework.

The testing programme must cover a range of assessments, tests, methodologies,
and tools applied on a regular basis, with results documented to support
supervisory review. DORA further requires that all detection mechanisms related
to anomalous activities and ICT-related incidents are regularly tested
(Article 25(3)(d)).

| DORA Article 25 requirement                                      | MethodAtlas feature                                                                           | Evidence produced |
|------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|-------------------|
| Sound and comprehensive testing programme                        | Structured inventory of security tests, classified by category                               | `ai_tags` column categorising tests by `auth`, `session`, `anomaly`, and other control types |
| Applied on a regular, documented basis                           | [`-emit-metadata`](cli-reference.md#-emit-metadata) timestamps every scan output             | Metadata comment block at top of each CSV output |
| Results available to competent authorities                       | SARIF 2.1.0 is a standardised, tool-importable format retained alongside build artefacts      | `security-tests.sarif` stored in CI artifact store |
| Detection mechanisms regularly tested (Art. 25(3)(d))           | Tests in `auth`, `session`, `anomaly` taxonomy categories are identified and counted          | `ai_tags` column filterable by category in CSV output |
| ICT systems tested at least annually                             | Scheduled CI scan with retained SARIF output satisfies the annual documentation requirement  | Timestamped SARIF artefacts with commit SHA in filename |
| Coverage not regressed between assessment periods                | [`-diff`](cli-reference.md#-diff) mode flags removed or reclassified security tests          | `delta.csv` with security-relevant count delta in summary line |

MethodAtlas provides evidence that a DORA-compliant testing programme
produces documented, regularly repeated, and supervisory-reviewable results
through its combination of `-emit-metadata`, SARIF output, and `-diff` mode.

See [DORA deployment guide](deployment/dora.md) for configuration details,
retention guidance, and air-gapped deployment options.

## Reproducibility and AI non-determinism

MethodAtlas separates two distinct layers with different reproducibility
properties.

**The structural layer is fully deterministic.** Method discovery (FQCN,
method name, LOC, source-level `@Tag` values, content hash) is driven entirely
by JavaParser AST analysis of the source files. Given the same source revision,
this layer always produces identical output, regardless of provider, model, or
time.

**The AI layer is non-deterministic by nature.** Language models use
probabilistic sampling. Even with the same model, same source, and same prompt,
a different run may produce a slightly different `ai_reason`, a different
`ai_confidence` value, or — rarely — a different `securityRelevant` verdict.
This is a fundamental property of all language model inference, not a defect
in MethodAtlas.

Two mechanisms mitigate AI non-determinism for compliance purposes:

1. **[`-ai-cache`](cli-reference.md#-ai-cache)** — once a class has been
   classified, its result is stored in a CSV indexed by SHA-256 content hash.
   Subsequent runs reuse the stored result without calling the provider. The
   scan output is therefore reproducible for all unchanged classes.

2. **[`-override-file`](cli-reference.md#-override-file)** — human-reviewed
   corrections are applied deterministically on every run and take precedence
   over AI output. An override entry sets confidence to `1.0` or `0.0`,
   reflecting the higher certainty of a human decision.

For evidence packages submitted to assessors, the recommended practice is to
treat the classified CSV (produced with `-ai -content-hash`) as the
authoritative record after human review. Re-running the scan on the same
commit using the same cache produces output identical to the reviewed artefact
for all unchanged classes; any new or changed classes are the only source of
variance.

## Further reading

- [OWASP SAMM v2 — Security Testing practice](https://owaspsamm.org/model/verification/security-testing/)
- [NIST SP 800-218 (SSDF)](https://csrc.nist.gov/pubs/sp/800/218/final)
- [ISO/IEC 27001:2022 Annex A 8.29 — overview](https://www.isms.online/iso-27001/annex-a-2022/8-29-security-testing-in-development-acceptance-2022/)
- [DORA — Regulation (EU) 2022/2554](https://eur-lex.europa.eu/eli/reg/2022/2554/oj/eng)
- [OWASP Testing Guide v4.2](https://owasp.org/www-project-web-security-testing-guide/)
- [MITRE CWE — Common Weakness Enumeration](https://cwe.mitre.org/)
