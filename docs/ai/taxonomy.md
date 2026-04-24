# Security taxonomy

The AI is constrained to a **closed tag set** so that providers cannot invent arbitrary categories. The built-in taxonomy covers ten security concerns:

| Tag | Meaning |
|---|---|
| `security` | Umbrella tag — every security-relevant method must carry this |
| `auth` | Authentication: identity verification, login, token handling |
| `access-control` | Authorisation: permission checks, role enforcement |
| `crypto` | Cryptographic operations: encryption, signing, key derivation |
| `input-validation` | Input sanitisation, bounds checking, format enforcement |
| `injection` | SQL injection, command injection, LDAP injection, etc. |
| `data-protection` | PII handling, masking, data-at-rest controls |
| `logging` | Audit logging, sensitive data in logs, log injection |
| `error-handling` | Error responses, exception leakage, fail-safe defaults |
| `owasp` | Explicit OWASP Top 10 or ASVS scenario coverage |

## Taxonomy modes

MethodAtlas ships two built-in taxonomy variants:

- **`default`** — descriptive, human-readable; suitable for teams building familiarity with the taxonomy.
- **`optimized`** — more compact; reduces prompt size and tends to improve classification reliability with smaller models.

Select with `-ai-taxonomy-mode optimized`.

## Custom taxonomy

To align the taxonomy with your organisation's internal controls framework, supply an external taxonomy file:

```bash
./methodatlas -ai -ai-taxonomy /path/to/taxonomy.txt /path/to/tests
```

The file contents replace the built-in taxonomy text verbatim. When both `-ai-taxonomy` and `-ai-taxonomy-mode` are provided, the external file takes precedence.
