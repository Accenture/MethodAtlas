# Security Policy

MethodAtlas is a security-classification tool used in regulated environments.
We treat every vulnerability report with the same urgency we ask of the teams
that adopt this tool.

## Supported versions

| Version stream | Supported |
| --- | --- |
| Latest release tag (`release@*`) | ✅ Full support |
| Older releases | ❌ No backports — please upgrade |

## Reporting a vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Report privately so that a fix can be prepared before the details become public:

1. Use **GitHub's private "Report a vulnerability" button** on the
   [Security tab](../../security/advisories/new) of this repository.
2. Alternatively, send an encrypted e-mail to `security@egothor.org`.
   Our PGP key fingerprint is published on [keys.openpgp.org](https://keys.openpgp.org).

Include as much detail as possible:

- Affected component and version
- Steps to reproduce or a minimal proof-of-concept
- Potential impact (confidentiality, integrity, availability, compliance)
- Whether you believe it is exploitable in a default configuration

## Response timeline

| Milestone | Target |
| --- | --- |
| Acknowledgement | within **2 business days** |
| Triage and severity assessment | within **5 business days** |
| Fix or mitigation plan communicated to reporter | within **30 days** for critical/high; **90 days** for lower severity |
| Public advisory and release | coordinated with reporter |

We follow [coordinated vulnerability disclosure](https://cheatsheetseries.owasp.org/cheatsheets/Vulnerability_Disclosure_Cheat_Sheet.html).
If you require a longer embargo for deployment, please say so in your report.

## Scope

The following are in scope:

- All Java source code in this repository (core engine, discovery plugins, GUI)
- The TypeScript scanner bundle embedded in `methodatlas-discovery-typescript`
- Build-time and runtime dependencies that we can influence (upgrade or patch)
- The generated SARIF and YAML outputs (injection, information disclosure)

The following are **out of scope**:

- Third-party AI provider APIs or model behaviour
- Vulnerabilities in the user's own codebase that MethodAtlas analyses
- Issues that require physical access to the analyst's workstation

## Coordinated disclosure credit

We will acknowledge researchers who report valid vulnerabilities in the release
notes and security advisory, unless you request anonymity.

## Regulatory context

MethodAtlas is developed to support security assessments in environments subject
to standards such as ISO 27001, SOC 2, and financial-sector regulations.
Security reports affecting audit-trail integrity or override-file confidentiality
are treated as **critical** regardless of CVSS score.
