# Why MethodAtlas

## The problem: invisible security test coverage

Modern Java projects routinely contain hundreds or thousands of JUnit test methods.
A fraction of those tests explicitly verify security properties — correct
authentication behaviour, cryptographic correctness, input validation, access
control boundaries — but they live side-by-side with purely functional tests and
are indistinguishable to anyone reading the test directory listing.

Without tooling, answering the question *"which of our tests cover security
requirements, and do they cover them completely?"* requires a manual audit of every
test file. That audit is time-consuming, error-prone, and does not stay current as
the codebase evolves.

MethodAtlas solves this by automating the discovery and classification step:
it reads source files lexically (without compiling them), identifies every JUnit 5
test method, and asks an AI provider to decide whether each method is security-
relevant, assign taxonomy tags, and provide a human-readable rationale.

---

## Where MethodAtlas fits in the SSDLC

MethodAtlas is a **testing-phase instrument** in the Secure Software Development
Life Cycle (SSDLC). It is not a replacement for static analysis, penetration
testing, or threat modelling. It complements those activities by maintaining
a continuously updated, machine-readable inventory of the security-test layer
of a project.

```
Plan → Design → Implement → Test ← MethodAtlas → Deploy → Operate
                                   (classify tests,
                                    emit SARIF, apply tags)
```

Typical integration points:

| Activity | MethodAtlas role |
|----------|-----------------|
| Nightly CI scan | Emit SARIF to GitHub Code Scanning; flag new unclassified tests |
| Sprint close | Run `-apply-tags` to annotate newly written security tests |
| Security review | Export CSV as evidence of security-test coverage for auditors |
| Air-gapped audit | Manual AI workflow (prepare / consume) produces the same CSV without network access |
| Regression gating | Content hashes (`-content-hash`) detect classes that changed since the last approved scan |

---

## Regulatory and framework context

Several widely adopted standards and frameworks explicitly require evidence of
security testing as part of the development and assurance process.  The
list below summarises what each framework demands and how MethodAtlas
helps satisfy that demand.

!!! note "Applicability varies by sector and organisation"
    The frameworks below are referenced for informational purposes only.
    Determining which of these apply to your organisation, and precisely how
    they must be satisfied, requires assessment by a qualified compliance
    professional.  Do not rely solely on this page as compliance guidance.

---

### OWASP SAMM v2 — Security Testing practice

The [OWASP Software Assurance Maturity Model (SAMM) v2](https://owaspsamm.org/model/verification/security-testing/)
defines a **Security Testing** practice under its *Verification* business function.
The practice is divided into two streams:

- **Stream A — Scalable Baseline:** establish automated security tests that run
  consistently across all applications; progressively tune them to reduce false
  positives and increase coverage.
- **Stream B — Deep Understanding:** complement automated tests with expert manual
  analysis focused on complex attack vectors.

At maturity level 1, Stream A requires that *automated security testing tools are
applied to the software and results are recorded*.  At maturity level 2, teams are
expected to *customise their test baseline to the specific technology stacks and
risk profile of each application*.

MethodAtlas supports the Stream A maturity progression by providing a repeatable,
automated inventory of security-relevant tests, enabling teams to track which test
classes have been classified, which have been tagged, and which still lack
coverage of specific taxonomy areas.

---

### NIST SP 800-218 (SSDF) — Practice PW.8

The [NIST Secure Software Development Framework (SSDF)](https://csrc.nist.gov/pubs/sp/800/218/final),
published as Special Publication 800-218, defines practice **PW.8:
"Test Executable Code to Identify Vulnerabilities and Verify Compliance with
Security Requirements"** within the *Produce Well-Secured Software (PW)* category.

PW.8 tasks include executing security-focused tests, reviewing and analysing
results, and remediating identified vulnerabilities before release. The SSDF
emphasises that organisations should be able to demonstrate which tests address
which security requirements.

MethodAtlas produces structured, traceable output (CSV, SARIF) that can feed
directly into evidence packages required for SSDF conformance reporting, mapping
each test method to AI-assigned taxonomy tags that correspond to security
requirement categories.

---

### ISO/IEC 27001:2022 — Annex A Control 8.29

[ISO/IEC 27001:2022](https://www.iso.org/standard/27001) Annex A Control **8.29 —
Security Testing in Development and Acceptance** mandates that organisations define
and implement security testing processes throughout the software development life
cycle and before systems are accepted into production.

According to publicly available guidance on this control, auditors assess
conformance by looking for:

- A documented security testing plan with defined acceptance criteria.
- Test results that can be traced back to specific security requirements.
- Evidence that releases were blocked or delayed when security tests failed.

MethodAtlas addresses the *traceability* requirement directly: its CSV and SARIF
outputs record which test methods were classified, which taxonomy tags were
assigned, and when the scan was executed (via `-emit-metadata`).  The content-hash
feature enables auditors to correlate a specific scan result to the exact source
revision that produced it.

---

### DORA (EU Regulation 2022/2554) — Article 25

The [Digital Operational Resilience Act (DORA)](https://eur-lex.europa.eu/eli/reg/2022/2554/oj/eng),
which applies to financial entities operating in the EU, requires in **Article 25**
that covered entities *"establish, maintain and review a sound and comprehensive
digital operational resilience testing programme"* as an integral part of their
ICT risk-management framework.

The testing programme must cover a range of assessments, tests, methodologies,
and tools applied on a regular basis, with results documented to support
supervisory review.  DORA further requires that *all detection mechanisms related
to anomalous activities and ICT-related incidents shall be regularly tested*.

For software teams in scope, MethodAtlas can be part of the evidence that security
test coverage is maintained and repeated across development cycles.  The SARIF
output integrates with code scanning dashboards that provide the timestamped,
per-commit audit trail DORA supervisors may request.

---

## Why AI-assisted classification?

Manual classification of hundreds of tests is feasible once; keeping it current
across active development is not.  AI-assisted classification offers:

- **Speed:** an entire test class is classified in seconds.
- **Consistency:** the same taxonomy is applied uniformly, regardless of which
  developer wrote the test or how it is named.
- **Rationale:** the `ai_reason` field documents *why* a method was classified as
  security-relevant, making the classification defensible during review.
- **Automation:** the tool runs in CI with no human intervention when an API
  provider is available, or via the manual workflow in restricted environments.

The taxonomy applied by MethodAtlas is based on established security categories
(authentication, authorisation, cryptography, input validation, session management,
and others) that align with the OWASP Testing Guide and common CWE groupings.

---

## Further reading

- [OWASP SAMM v2 — Security Testing practice](https://owaspsamm.org/model/verification/security-testing/)
- [NIST SP 800-218 (SSDF)](https://csrc.nist.gov/pubs/sp/800/218/final)
- [ISO/IEC 27001:2022 Annex A 8.29 — overview](https://www.isms.online/iso-27001/annex-a-2022/8-29-security-testing-in-development-acceptance-2022/)
- [DORA — Regulation (EU) 2022/2554, Article 25](https://eur-lex.europa.eu/eli/reg/2022/2554/oj/eng)
- [OWASP Testing Guide v4.2](https://owasp.org/www-project-web-security-testing-guide/)
- [MITRE CWE — Common Weakness Enumeration](https://cwe.mitre.org/)
