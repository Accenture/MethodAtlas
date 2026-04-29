# NIST SP 800-218 (SSDF)

NIST Special Publication 800-218, the *Secure Software Development
Framework* (SSDF), was published by the National Institute of Standards and
Technology in February 2022. It defines a set of high-level practices for
integrating security into the software development life cycle. US federal
agencies are required to follow SSDF by [OMB Memorandum M-22-18](https://www.whitehouse.gov/wp-content/uploads/2022/09/M-22-18.pdf);
many commercial organisations adopt it voluntarily or in response to
contractual requirements from US government customers.

## Relevant practice

**Practice PW.8 — Test Executable Code to Identify Vulnerabilities and
Verify Compliance with Security Requirements** falls within the
*Produce Well-Secured Software* (PW) category of the SSDF. Its tasks
include:

- **PW.8.1**: Test the code using techniques and tools appropriate to the
  risk profile, and review the results.
- **PW.8.2**: Verify the correctness and effectiveness of all security
  controls in the code by performing testing, and document the results.

The framework emphasises that organisations should be able to demonstrate
*which tests address which security requirements* — a structured traceability
requirement that informal test naming conventions or coverage metrics alone
cannot satisfy.

## Control mapping

| SSDF task                                                                 | MethodAtlas feature                                                                          | Evidence produced |
|---------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|-------------------|
| PW.8.1 — Test using techniques appropriate to risk                        | AI taxonomy assigns tags tied to risk categories (`auth`, `crypto`, `injection`, `session`)  | `ai_tags` column in CSV/SARIF |
| PW.8.1 — Review results                                                   | `ai_reason` column provides human-readable rationale for each classification decision         | `ai_reason` values in CSV output |
| PW.8.1 — Document testing approach                                        | [`-emit-metadata`](../cli-reference.md#-emit-metadata) prepends scan timestamp and MethodAtlas version | Metadata comment block at top of CSV output |
| PW.8.2 — Verify security controls and document results                    | SARIF output provides a machine-readable, timestamped record of which test methods cover which controls | `security-tests.sarif` per release |
| PW.8.2 — Trace tests to security requirements                             | Taxonomy tags provide a structured mapping from test method to requirement category           | `ai_tags` column; custom taxonomy via [`-ai-taxonomy`](../cli-reference.md#-ai-taxonomy) |

## Recommended configuration

**Context:** PW.8 requires that organisations document *which* tests cover
*which* security requirements. The following command produces output that
satisfies this traceability requirement directly.

**MethodAtlas capability:** AI classification with
[`-content-hash`](../cli-reference.md#-content-hash) and
[`-emit-metadata`](../cli-reference.md#-emit-metadata) produces a
machine-verifiable link between each test method and its security requirement
category.

```bash
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash \
  -emit-metadata \
  src/test/java \
  > security-tests.csv
```

For tool-importable output compatible with code scanning platforms:

```bash
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -sarif \
  -security-only \
  -content-hash \
  -emit-metadata \
  src/test/java \
  > security-tests.sarif
```

**Evidence output:** `security-tests.csv` provides the PW.8.1 review record;
`security-tests.sarif` provides the PW.8.2 documentation record in a
standardised, tool-importable format.

## Traceability model

The SSDF requires that test results can be traced to specific security
requirements. MethodAtlas implements this through three complementary
mechanisms:

1. **Taxonomy tags** (`ai_tags` column): each security-relevant method is
   assigned one or more tags from the built-in security taxonomy (or a custom
   taxonomy provided via
   [`-ai-taxonomy`](../cli-reference.md#-ai-taxonomy)). Tags correspond to
   requirement categories such as `auth`, `crypto`, `injection`, and `session`.

2. **Content hash** (`content_hash` column, enabled with
   [`-content-hash`](../cli-reference.md#-content-hash)): a SHA-256
   fingerprint of each test class's source. Ties the result to a specific
   source revision independent of version control metadata.

3. **Scan metadata** (prepended with
   [`-emit-metadata`](../cli-reference.md#-emit-metadata)): records the scan
   timestamp and MethodAtlas version alongside the results.

## Using a custom taxonomy

If your organisation maps tests to a specific control framework (NIST SP
800-53, CIS Controls, or an internal control catalogue), the built-in
taxonomy can be replaced with a domain-specific one:

```bash
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -ai-taxonomy /path/to/nist-80053-taxonomy.txt \
  -content-hash \
  src/test/java
```

The taxonomy file contains the tag definitions and classification criteria
presented to the AI model. See [Security Taxonomy](../ai/taxonomy.md) for
the file format.

## Artefact package

| Artefact            | Flags                              | Purpose |
|---------------------|------------------------------------|---------|
| CSV inventory       | `-content-hash -emit-metadata`     | Primary evidence record; human-readable; supports PW.8.1 review |
| SARIF output        | `-sarif -security-only`            | Tool-importable; supports PW.8.2 documentation; suitable for dashboard integration |
| Taxonomy file       | [`-ai-taxonomy`](../cli-reference.md#-ai-taxonomy) (if custom) | Documents classification criteria used; links tags to NIST control identifiers |

## Further reading

- [NIST SP 800-218 — Secure Software Development Framework](https://csrc.nist.gov/pubs/sp/800/218/final)
- [OMB M-22-18 — Enhancing the Security of the Software Supply Chain](https://www.whitehouse.gov/wp-content/uploads/2022/09/M-22-18.pdf)
- [NIST SP 800-53 Rev. 5 — Security and Privacy Controls](https://csrc.nist.gov/pubs/sp/800/53/r5/upd1/final)
- [MethodAtlas — Security Taxonomy](../ai/taxonomy.md)
- [MethodAtlas — Output Formats](../output-formats.md)
