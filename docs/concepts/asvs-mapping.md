# OWASP ASVS Mapping

The [OWASP Application Security Verification Standard (ASVS)](https://owasp.org/www-project-application-security-verification-standard/)
defines a framework of security requirements for web applications, organised
into verification requirements chapters. Version 4.0 is the current stable
release.

This page maps each MethodAtlas security taxonomy tag to the corresponding
ASVS chapter and describes how to use the mapping to plan test coverage
against specific ASVS verification levels.

## Mapping table

| MethodAtlas tag | ASVS chapter | ASVS title | Notes |
|---|---|---|---|
| `auth` | V2 | Authentication Verification Requirements | Covers identity verification, credential storage, MFA, account recovery |
| `auth` | V3 | Session Management Verification Requirements | Session creation, binding, expiry, and termination |
| `access-control` | V4 | Access Control Verification Requirements | Authorisation checks, role enforcement, resource boundaries |
| `input-validation` | V5 | Validation, Sanitization and Encoding | Input format enforcement, boundary checking, output encoding |
| `injection` | V5 | Validation, Sanitization and Encoding | Injection prevention requirements fall within V5 in ASVS 4.0 |
| `crypto` | V6 | Stored Cryptography Verification Requirements | At-rest encryption, key management, hashing algorithms |
| `crypto` | V9 | Communications Verification Requirements | TLS configuration, certificate validation, transport encryption |
| `logging` | V7 | Error Handling and Logging Verification Requirements | Audit log content, sensitive data in logs, log protection |
| `error-handling` | V7 | Error Handling and Logging Verification Requirements | Error response design, exception handling, fail-safe defaults |
| `data-protection` | V8 | Data Protection Verification Requirements | PII handling, data minimisation, sensitive data at rest and in transit |
| `owasp` | Multiple | (general coverage) | Methods tagged `owasp` address scenarios from multiple ASVS chapters |

## ASVS verification levels

ASVS defines three verification levels with increasing security requirements:

| Level | Target | Description |
|---|---|---|
| L1 | All software | Baseline security controls; verifiable with black-box testing |
| L2 | Applications handling sensitive data | Standard controls; most commercial and enterprise applications |
| L3 | Critical systems | Advanced controls; financial infrastructure, healthcare, high-value targets |

MethodAtlas taxonomy tags align primarily with L2 and L3 verification
requirements — the levels where structured, AI-assisted test classification
adds the most value. L1 requirements are typically addressed by automated
scanning tools rather than bespoke test code.

## Using the mapping for coverage planning

### Identifying gaps by ASVS chapter

The following shell command counts security-relevant tests per taxonomy tag
in a scan output, allowing you to see which ASVS chapters have test coverage
and which are absent:

```bash
awk -F',' 'NR > 1 && $5 == "true" {print $7}' security-tests.csv \
  | tr ';' '\n' \
  | grep -v '^security$' \
  | sort | uniq -c | sort -rn
```

Map the resulting tag counts to the table above to identify which ASVS
chapters lack test coverage. An absent tag does not necessarily indicate a
gap — some ASVS chapters may not apply to the application's architecture —
but it warrants deliberate review.

### Example: coverage assessment for an L2 application

For an application targeting ASVS L2, the following tags represent minimum
coverage expectations:

| ASVS chapter | Expected MethodAtlas tag | Minimum test count guidance |
|---|---|---|
| V2 — Authentication | `auth` | At least one test per authentication mechanism (password, token, MFA) |
| V3 — Session Management | `auth` | Session expiry, fixation prevention, and logout |
| V4 — Access Control | `access-control` | At least one negative-case test per protected resource boundary |
| V5 — Validation | `input-validation`, `injection` | Tests for each external input vector (HTTP, file upload, API) |
| V6 — Stored Cryptography | `crypto` | Password hashing algorithm and parameters; sensitive field encryption |
| V7 — Logging | `logging` | Audit events for authentication and authorisation failures |
| V8 — Data Protection | `data-protection` | PII handling and retention tests |

### Reporting coverage to auditors

For ASVS-based security assessments, supplement the standard MethodAtlas
SARIF output with a coverage summary that groups findings by ASVS chapter:

```bash
# Produce a per-chapter count from a security-only scan
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -security-only \
  src/test/java \
  | awk -F',' 'NR > 1 {
      n = split($7, tags, ";")
      for (i = 1; i <= n; i++) {
        if (tags[i] != "security" && tags[i] != "")
          counts[tags[i]]++
      }
    }
    END {
      for (tag in counts) print counts[tag], tag
    }' \
  | sort -rn
```

Present the output alongside the ASVS chapter mapping table above.

## Custom taxonomy aligned to ASVS

Teams whose test suites should be explicitly mapped to ASVS requirement
identifiers can replace the built-in taxonomy with one whose tag names
correspond to ASVS chapter numbers:

```text
SECURITY TAXONOMY

Classify each test method using only the tags below.

Tag: security
Covers: Apply to every security-relevant test method.

Tag: asvs-v2-auth
Covers: ASVS V2 Authentication — identity verification, credential
management, multi-factor authentication.

Tag: asvs-v3-session
Covers: ASVS V3 Session Management — session tokens, binding, expiry,
logout.

Tag: asvs-v4-access
Covers: ASVS V4 Access Control — authorisation decisions, role checks,
resource ownership.

Tag: asvs-v5-validation
Covers: ASVS V5 Validation, Sanitization and Encoding — input validation,
output encoding, injection prevention.

Tag: asvs-v6-crypto
Covers: ASVS V6 Stored Cryptography — at-rest encryption, key management,
password hashing.

Tag: asvs-v7-logging
Covers: ASVS V7 Error Handling and Logging — audit logs, error responses,
sensitive data exposure.

Tag: asvs-v8-data
Covers: ASVS V8 Data Protection — PII handling, data minimisation,
sensitive data controls.
```

Supply this file with `-ai-taxonomy` to produce output whose tags map
directly to ASVS chapter numbers.

See [Custom Taxonomy](../ai/custom-taxonomy.md) for the full file format
and usage guidance.

## Further reading

- [OWASP ASVS v4.0 — full standard](https://owasp.org/www-project-application-security-verification-standard/)
- [OWASP ASVS v4.0 — GitHub repository](https://github.com/OWASP/ASVS)
- [OWASP Testing Guide v4.2](https://owasp.org/www-project-web-security-testing-guide/)
- [MethodAtlas — Security Taxonomy](../ai/taxonomy.md)
- [MethodAtlas — Custom Taxonomy](../ai/custom-taxonomy.md)
- [MethodAtlas — Compliance & Standards](../compliance.md)
