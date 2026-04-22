# Why MethodAtlas

## The problem

Modern Java projects routinely contain hundreds or thousands of JUnit test methods.
A fraction of those tests explicitly verify security properties — correct
authentication behaviour, cryptographic correctness, input validation, access
control boundaries — but they live side-by-side with purely functional tests and
are indistinguishable to anyone reading the test directory listing.

Without tooling, answering the question *"which of our tests cover security
requirements, and do they cover them completely?"* requires a manual audit of
every test file. That audit is time-consuming, error-prone, and does not stay
current as the codebase evolves.

MethodAtlas automates the discovery and classification step: it reads source
files lexically (without compiling them), identifies every JUnit 5 test method,
and asks an AI provider to decide whether each method is security-relevant,
assign taxonomy tags, and provide a human-readable rationale.

## Where it fits in the SSDLC

MethodAtlas is a **testing-phase instrument** in the Secure Software Development
Life Cycle. It is not a replacement for static analysis, penetration testing, or
threat modelling — it complements those activities by maintaining a continuously
updated, machine-readable inventory of the security-test layer of a project.

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
| Air-gapped audit | Manual AI workflow produces the same CSV without network access |
| Regression gating | Content hashes detect classes that changed since the last approved scan |

## Why AI-assisted classification?

Manual classification of hundreds of tests is feasible once; keeping it current
across active development is not. AI-assisted classification offers:

- **Speed** — an entire test class is classified in seconds.
- **Consistency** — the same taxonomy is applied uniformly regardless of who
  wrote the test or how it is named.
- **Rationale** — the `ai_reason` field documents *why* a method was classified
  as security-relevant, making the classification defensible during review.
- **Automation** — the tool runs in CI with no human intervention when an API
  provider is available, or via the [manual workflow](usage-modes/manual.md) in
  restricted environments.

The taxonomy applied by MethodAtlas covers categories that align with the
OWASP Testing Guide and common CWE groupings: authentication, authorisation,
cryptography, input validation, session management, and others.

## Regulatory context

Multiple standards and frameworks require evidence of security testing as part
of the software development and assurance process. See the
[Compliance & Standards](compliance.md) page for a framework-by-framework
overview of how MethodAtlas supports those requirements.
