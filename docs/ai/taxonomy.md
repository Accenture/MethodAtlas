# Security taxonomy

The security taxonomy is the closed set of tags the AI is permitted to assign. Constraining the AI to a fixed vocabulary ensures that output is consistent, filterable, and auditable across runs and models.

## When to use

The built-in taxonomy is appropriate for most Java web, API, and enterprise applications. If your domain has security control names that differ from the default tags — or your audit framework requires alignment with specific terminology — see [Custom Security Taxonomy](custom-taxonomy.md) for how to replace the built-in taxonomy entirely.

## Built-in tags

The AI is constrained to a **closed tag set** so that providers cannot invent arbitrary categories. The built-in taxonomy covers ten security concerns:

| Tag                 | Meaning                                                              |
|---------------------|----------------------------------------------------------------------|
| `security`          | Umbrella tag — every security-relevant method must carry this        |
| `auth`              | Authentication: identity verification, login, token handling         |
| `access-control`    | Authorisation: permission checks, role enforcement                   |
| `crypto`            | Cryptographic operations: encryption, signing, key derivation        |
| `input-validation`  | Input sanitisation, bounds checking, format enforcement              |
| `injection`         | SQL injection, command injection, LDAP injection, etc.               |
| `data-protection`   | PII handling, masking, data-at-rest controls                         |
| `logging`           | Audit logging, sensitive data in logs, log injection                 |
| `error-handling`    | Error responses, exception leakage, fail-safe defaults               |
| `owasp`             | Explicit OWASP Top 10 or ASVS scenario coverage                      |

## Taxonomy modes

MethodAtlas ships two built-in taxonomy variants:

- **`default`** — descriptive, human-readable; suitable for teams building familiarity with the taxonomy.
- **`optimized`** — more compact; reduces prompt size and tends to improve classification reliability with smaller models.

Select the mode with the `-ai-taxonomy-mode` flag:

```bash
./methodatlas -ai -ai-taxonomy-mode optimized src/test/java
```

Or via YAML:

```yaml
ai:
  enabled: true
  taxonomyMode: optimized
```

## Custom taxonomy

To align the taxonomy with your organisation's internal controls framework, supply an external taxonomy file:

```bash
./methodatlas -ai -ai-taxonomy /path/to/taxonomy.txt /path/to/tests
```

The file contents replace the built-in taxonomy text verbatim. When both `-ai-taxonomy` and `-ai-taxonomy-mode` are provided, the external file takes precedence.

See [Custom Security Taxonomy](custom-taxonomy.md) for the file format, domain-specific examples (fintech, healthcare, industrial), and taxonomy design guidelines.

## Taxonomy version stamp

Every AI-classified record carries the version of the taxonomy that produced it, so an auditor reading a classification years later can tell which controlled vocabulary applied. The value appears as the `ai_taxonomy_version` CSV column (always the last column), the `ai_taxonomy_version` JSON field, and the `aiTaxonomyVersion` SARIF result property; it is blank or omitted for records with no AI result.

| Taxonomy in use | `ai_taxonomy_version` value |
|---|---|
| Built-in `default` | `built-in/default@<revision>` (currently `built-in/default@1`) |
| Built-in `optimized` | `built-in/optimized@<revision>` (currently `built-in/optimized@1`) |
| Custom `-ai-taxonomy` file | `sha256:<64 lowercase hex>` — SHA-256 of the file's raw bytes |

Rules:

- The built-in `<revision>` is an integer that is incremented whenever the built-in taxonomy text changes in any way (including wording). A unit test pins a digest of each built-in text, so a change without a revision bump fails the build.
- The custom-file format has no version field, so its version is a content fingerprint: the same file bytes always give the same value, and any edit gives a different one. It is the same digest the reproducibility receipt records as `taxonomyFileSha256`, so the per-record stamp and the receipt always agree. A custom file therefore has no human-readable version; keep a copy of the file (or rely on the receipt path) to know what the fingerprint refers to.
- The stamp is the taxonomy configured for the **current run**. For the AI result cache (`-ai-cache`) this is nevertheless truthful: the cache key includes the taxonomy version (`<prompt signature>|<taxonomy version>`), so a taxonomy change is a cache miss and a classification is only ever reused under the taxonomy that produced it. Legacy cache entries that carry no signature (for example a legacy scan CSV passed to `-ai-cache`) have no taxonomy provenance and are never reused for classification; they are re-queried.
- **`-manual-consume` caveat.** Manual mode is a two-step workflow: `-manual-prepare` builds the prompts under the taxonomy configured at *prepare* time, and `-manual-consume` ingests the responses and stamps the records with the taxonomy configured at *consume* time. MethodAtlas does not record the prepare-time taxonomy in the work files, so if the taxonomy (mode or `-ai-taxonomy` file) differs between the two runs the stamp will name the consume-time taxonomy even though the responses were produced under the prepare-time one. Run both steps with identical taxonomy settings; the reproducibility receipt of each step records the settings that applied.
- The GUI always uses the built-in `default` taxonomy; its evidence CSV carries the same column (GUI audit schema version 3).

See [CLI reference](../cli-reference.md) for the full flag descriptions.
