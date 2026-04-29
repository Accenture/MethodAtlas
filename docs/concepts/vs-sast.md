# MethodAtlas and SAST Tools

MethodAtlas and static application security testing (SAST) tools are complementary: each answers a question the other cannot.

## When to use each tool

Use a SAST tool when you need to find vulnerabilities in production code. Use MethodAtlas when you need to inventory and measure the quality of the tests that verify your security controls. The two tools operate on different inputs, produce different outputs, and answer different questions — they are most valuable when used together.

Static application security testing (SAST) tools and MethodAtlas address
distinct problems in the security verification process. Understanding where
each tool operates is essential for configuring them correctly, avoiding
redundant effort, and producing the evidence artefacts that auditors require.

## What SAST tools do

SAST tools analyse production source code, bytecode, or compiled binary
artefacts to identify security vulnerabilities in the application under
development. Their primary question is: **does this production code contain
an exploitable weakness?**

Representative tools and their primary detection approaches:

| Tool | Approach | Primary findings |
|---|---|---|
| Checkmarx SAST | Taint-flow data-flow analysis | Injection flaws, path traversal, insecure data flows |
| Veracode Static Analysis | Pattern and taint-flow analysis | OWASP Top 10, CWE catalogue coverage |
| Semgrep | Rule-based pattern matching | Configurable rule sets; community OWASP and CVE patterns |
| SonarQube (Security) | Rule-based analysis | OWASP Top 10, CWE Top 25, code quality metrics |
| SpotBugs + Find Security Bugs | JVM bytecode bug patterns | Security misconfigurations, crypto API misuse |

A typical SAST finding reads:

> *Possible SQL injection at `UserRepository.java:47` — user-supplied input
> reaches a JDBC query concatenation point without sanitisation (CWE-89).*

These tools operate on what the application **does** and whether it
**does it safely**.

## What MethodAtlas does

MethodAtlas operates on test source code. It identifies which test methods
in the project's test suite were written to verify security properties,
classifies them by taxonomy tag, and — when AI enrichment is enabled —
measures how thoroughly each test asserts outcomes rather than merely
exercising code paths.

A typical MethodAtlas finding reads:

> *`AuthServiceTest.loginWithExpiredToken` is security-relevant
> (confidence: 0.92, taxonomy: auth). The method invokes the authentication
> service but does not assert the return value — interaction score: 1.0.*

MethodAtlas answers: **has the team written tests that would detect a
regression in this security property?** It does not inspect production code
for vulnerabilities.

## Comparison

| Dimension | SAST tool | MethodAtlas |
|---|---|---|
| Input analysed | Production source code or bytecode | Test source code only |
| Central question | Is this production code vulnerable? | Is this security control tested? |
| Output artefact | Vulnerability report (SARIF, CSV, issue tracker) | Security test inventory (SARIF, CSV) |
| Compilation required | Varies by tool | No — source-only AST parsing |
| AI component | No | Optional — for semantic classification |
| Outbound network calls | None | Optional — when an AI provider is configured |
| Compliance artefact produced | Evidence of vulnerability scanning | Evidence of security test coverage |

## What each tool misses independently

A SAST tool may report that `UserService.changePassword` contains a
privilege-escalation path. A developer corrects the production code and the
SAST finding clears. The SAST tool cannot determine whether the team has
written a regression test that would catch a recurrence of that flaw — only
that the current production code does not exhibit it.

Conversely, MethodAtlas can identify that
`SecurityTest.changePasswordAcrossAccounts` is the only test covering
privilege escalation in `UserService`. It cannot determine whether the
production implementation is correct — only that the test exists and, when
AI enrichment is enabled, whether the test asserts its intended outcome
rather than just calling the method under test.

Neither tool replaces the other. Together they close a gap that neither
covers independently: SAST verifies that the production implementation is
correct; MethodAtlas verifies that correctness is continuously tested.

## Relationship to code coverage tools

Code coverage tools such as JaCoCo (Java), Istanbul (JavaScript), or
coverage.py (Python) measure instruction or branch execution during a test
run. A 95 % instruction coverage figure reports that 95 % of production
code statements were reached by *some* test — but it does not distinguish
coverage contributed by a functional test, a performance test, or a
dedicated security test.

MethodAtlas addresses a semantic question that execution metrics cannot:
**which test methods were written with security intent?** Coverage reports
and MethodAtlas output are complementary.

## Recommended pipeline configuration

For projects in regulated environments, a layered approach places each tool
at the stage where it provides the highest value:

| Pipeline stage | Tool | Purpose |
|---|---|---|
| On every commit | SAST (SonarQube, Semgrep) | Detect new vulnerabilities in production code early |
| On pull request | MethodAtlas `-github-annotations` | Flag security tests with weak assertions before merge |
| Nightly or weekly | MethodAtlas `-sarif -security-only` | Maintain a current, audit-ready security test inventory |
| Pre-release gate | MethodAtlas `-diff` | Detect removed or degraded security tests before shipping |

See [GitHub Actions](../ci/github-actions.md) and
[GitLab CI](../ci/gitlab.md) for complete workflow examples.

## Further reading

- [OWASP Software Assurance Maturity Model (SAMM) v2 — Security Testing](https://owaspsamm.org/model/verification/security-testing/)
- [OWASP Web Security Testing Guide v4.2](https://owasp.org/www-project-web-security-testing-guide/)
- [NIST SP 800-218 — Secure Software Development Framework (SSDF)](https://csrc.nist.gov/pubs/sp/800/218/final)
- [MITRE CWE — Common Weakness Enumeration](https://cwe.mitre.org/)
