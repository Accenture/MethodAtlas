# Compliance & Standards

Several widely adopted frameworks explicitly require evidence of security testing
as part of the development and assurance process. This page summarises what each
framework demands and how MethodAtlas helps satisfy that demand.

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
    [Manual AI workflow](../usage-modes/manual.md) performs classification
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

MethodAtlas supports the Stream A maturity progression by providing a repeatable,
automated inventory of security-relevant tests, enabling teams to track which
classes have been classified, which have received taxonomy tags, and which still
lack coverage of specific security categories.

## NIST SP 800-218 (SSDF) — Practice PW.8

The [NIST Secure Software Development Framework (SSDF)](https://csrc.nist.gov/pubs/sp/800/218/final),
Special Publication 800-218, defines practice **PW.8: "Test Executable Code to
Identify Vulnerabilities and Verify Compliance with Security Requirements"**
within the *Produce Well-Secured Software* category.

PW.8 tasks include executing security-focused tests, reviewing and analysing
results, and remediating identified vulnerabilities before release. The framework
emphasises that organisations should be able to demonstrate which tests address
which security requirements.

MethodAtlas produces structured, traceable output (CSV, SARIF) that can feed
directly into evidence packages required for SSDF conformance reporting, with
each test method mapped to AI-assigned taxonomy tags that correspond to security
requirement categories.

## ISO/IEC 27001:2022 — Annex A Control 8.29

[ISO/IEC 27001:2022](https://www.iso.org/standard/27001) Annex A Control
**8.29 — Security Testing in Development and Acceptance** mandates that
organisations define and implement security testing processes throughout the
software development life cycle and before systems are accepted into production.

According to publicly available guidance on this control, auditors assess
conformance by looking for:

- A documented security testing plan with defined acceptance criteria.
- Test results that can be traced back to specific security requirements.
- Evidence that releases were blocked or delayed when security tests failed.

MethodAtlas addresses the traceability requirement directly: its CSV and SARIF
outputs record which test methods were classified, which taxonomy tags were
assigned, and when the scan was executed (via `-emit-metadata`). The
`-content-hash` feature enables auditors to correlate a specific scan result to
the exact source revision that produced it.

## DORA (EU 2022/2554) — Article 25

The [Digital Operational Resilience Act (DORA)](https://eur-lex.europa.eu/eli/reg/2022/2554/oj/eng),
which applies to financial entities operating in the EU, requires in
**Article 25** that covered entities establish, maintain, and review a sound and
comprehensive digital operational resilience testing programme as an integral part
of their ICT risk-management framework.

The testing programme must cover a range of assessments, tests, methodologies,
and tools applied on a regular basis, with results documented to support
supervisory review. DORA further requires that all detection mechanisms related
to anomalous activities and ICT-related incidents are regularly tested.

For software teams in scope, MethodAtlas can form part of the evidence that
security test coverage is maintained and repeated across development cycles.
The SARIF output integrates with code scanning dashboards that provide the
timestamped, per-commit audit trail supervisors may request.

## Further reading

- [OWASP SAMM v2 — Security Testing practice](https://owaspsamm.org/model/verification/security-testing/)
- [NIST SP 800-218 (SSDF)](https://csrc.nist.gov/pubs/sp/800/218/final)
- [ISO/IEC 27001:2022 Annex A 8.29 — overview](https://www.isms.online/iso-27001/annex-a-2022/8-29-security-testing-in-development-acceptance-2022/)
- [DORA — Regulation (EU) 2022/2554](https://eur-lex.europa.eu/eli/reg/2022/2554/oj/eng)
- [OWASP Testing Guide v4.2](https://owasp.org/www-project-web-security-testing-guide/)
- [MITRE CWE — Common Weakness Enumeration](https://cwe.mitre.org/)
