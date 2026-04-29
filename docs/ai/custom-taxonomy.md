# Custom security taxonomy

A custom taxonomy file replaces the built-in classification vocabulary with one tailored to your domain, enabling MethodAtlas AI output to align directly with your organisation's internal controls framework, risk register, or regulatory terminology.

## When to use a custom taxonomy

The built-in taxonomy is appropriate for most projects. Consider a custom taxonomy when:

- Your domain has established security control names that differ from the built-in tags, and alignment with internal documentation matters for audit purposes.
- Your classification results should map directly to entries in an internal control catalogue, a risk register, or a regulatory framework your organisation reports against.
- The built-in taxonomy produces a high proportion of `owasp` catch-all tags where more specific categorisation would be more actionable.
- You want to exclude categories that are irrelevant to your application (for example, a batch-processing backend with no public HTTP interface may not need an `injection` tag).

## How it works

When the `-ai-taxonomy` flag is supplied, the contents of the specified file replace the built-in taxonomy text in the AI prompt. The AI model is instructed to classify each test method using only the tags defined in the file, and to return an empty tag set for methods that do not match any defined tag.

The file is plain text. Write it as you would a prompt instruction: clearly state the available tags, their names exactly as they should appear in output, and a concise definition of what each tag covers. Definitions that include concrete examples consistently produce more accurate classifications than abstract definitions.

## File format

```text
SECURITY TAXONOMY

Classify each test method using only the tags listed below. Use the tag
name exactly as written. A method may receive multiple tags. If no tag
applies, return an empty list.

Tag: security
Covers: Apply to every method classified as security-relevant. This tag
must always accompany any other tag below.

Tag: auth
Covers: Authentication — identity verification, login flows, token
issuance and validation, credential storage, session creation and
termination, multi-factor authentication.
Examples: login rejects wrong password, expired token is rejected,
MFA challenge is enforced.

Tag: access-control
Covers: Authorisation — permission checks, role enforcement, resource
ownership boundaries, privilege escalation prevention.
Examples: user cannot access another user's data, admin-only endpoint
rejects regular users, role assignment is validated.

Tag: crypto
Covers: Cryptographic operations — encryption, decryption, digital
signatures, key derivation, hashing, certificate validation.
Examples: ciphertext integrity is verified, weak cipher is rejected,
PBKDF2 parameters meet minimum iterations.

Tag: input-validation
Covers: Input sanitisation, format enforcement, boundary checking,
rejection of malformed or oversized input.
Examples: SQL metacharacters are rejected, negative quantities are
rejected, overly long strings are truncated.

Tag: injection
Covers: Injection attack prevention — SQL injection, command injection,
LDAP injection, expression language injection, XML injection.
Examples: parameterised queries are used, shell metacharacters are
escaped, user input does not reach a query constructor directly.

Tag: data-protection
Covers: Personally identifiable information handling, data masking,
at-rest encryption, retention and deletion controls.
Examples: PII is masked in logs, deleted records are not recoverable,
sensitive fields are encrypted before storage.

Tag: logging
Covers: Audit logging completeness and correctness, absence of sensitive
data in logs, tamper-evident log records.
Examples: failed login attempts are logged, passwords do not appear in
log output, log entries contain required audit fields.

Tag: error-handling
Covers: Error response design — absence of stack traces in API responses,
fail-safe defaults, information leakage through error messages.
Examples: internal exception details are not returned to the caller,
authentication errors return a generic message.

Tag: owasp
Covers: Explicit OWASP Top 10 or OWASP ASVS scenario coverage not
captured by a more specific tag above.
```

Save this file as `taxonomy.txt` (or any name) and supply it with:

```bash
./methodatlas \
  -ai -ai-taxonomy /path/to/taxonomy.txt \
  src/test/java
```

Or via YAML:

```yaml
ai:
  enabled: true
  taxonomy: /path/to/taxonomy.txt
```

## Domain-specific examples

### Fintech and payment processing

Regulatory requirements in payment processing (PCI-DSS, PSD2) and financial services (MiFID II, DORA) introduce controls that do not map precisely to the built-in taxonomy:

```text
SECURITY TAXONOMY

Tag: security
Covers: Apply to every security-relevant test method.

Tag: authentication
Covers: Customer identity verification, strong authentication, step-up
authentication for high-value transactions, session management.

Tag: authorisation
Covers: Account ownership checks, transaction authorisation, delegation
and mandate verification, four-eyes principle enforcement.

Tag: funds-transfer-integrity
Covers: Idempotency of payment transactions, duplicate payment prevention,
amount and currency integrity checks, IBAN/BIC validation.

Tag: fraud-detection
Covers: Velocity controls, transaction limits, anomaly signal generation,
device fingerprint and behavioural checks.

Tag: pci-cardholder-data
Covers: PAN truncation, CVV non-storage, cardholder data masking,
tokenisation correctness.

Tag: psd2-sca
Covers: Strong Customer Authentication flows as required by PSD2 RTS:
possession factor, knowledge factor, and inherence factor verification.

Tag: audit-trail
Covers: Completeness of financial event logs, tamper-evidence, required
regulatory fields in transaction records.
```

### Healthcare and life sciences

Healthcare applications handling Protected Health Information (PHI) are subject to HIPAA, HL7 FHIR access controls, and internal data-governance requirements:

```text
SECURITY TAXONOMY

Tag: security
Covers: Apply to every security-relevant test method.

Tag: phi-access-control
Covers: Patient record access boundary enforcement, minimum-necessary
principle, consent-based access, break-the-glass scenario handling.

Tag: phi-de-identification
Covers: Correct application of Safe Harbour or Expert Determination de-
identification methods; verification that de-identified datasets contain
no residual direct or quasi-identifiers.

Tag: phi-audit
Covers: HIPAA-required audit logging for all PHI access events: who
accessed which record, when, from where, and for what purpose.

Tag: authentication
Covers: Clinician and staff identity verification, session timeout
enforcement, emergency access procedures.

Tag: data-integrity
Covers: Clinical data write-back validation, checksum verification,
tamper detection for diagnostic results and prescriptions.

Tag: consent
Covers: Patient consent capture, storage, withdrawal, and enforcement
in data-sharing workflows.
```

### Industrial and embedded systems

```text
SECURITY TAXONOMY

Tag: security
Covers: Apply to every security-relevant test method.

Tag: command-integrity
Covers: Authenticity and authorisation of commands sent to actuators or
PLCs; prevention of replay attacks; command sequence validation.

Tag: firmware-validation
Covers: Digital signature verification of firmware images before
application; downgrade attack prevention.

Tag: network-isolation
Covers: Enforcement of network segmentation rules; absence of unexpected
outbound connections from the control network.

Tag: tamper-detection
Covers: Physical or logical tamper-event detection and response;
hardware security module (HSM) interaction correctness.
```

## Taxonomy design guidelines

**Write definitions in terms of test behaviour, not production code.**
The AI classifies test methods based on what assertions the test makes. A definition like *"covers methods that call `PasswordEncoder.encode`"* leads to poor results; a definition like *"covers tests that verify passwords are stored in hashed form and that the original plaintext is not recoverable"* leads to accurate ones.

**Include concrete examples in each definition.**
One to three examples of what a test in this category would verify improve classification accuracy considerably, particularly for tags whose names are ambiguous.

**Limit the tag count.**
The built-in taxonomy has ten tags. Taxonomies with more than fifteen tags produce diminishing returns in specificity and increase the risk of the model making arbitrary choices between similar tags. If you find yourself needing more tags, consider whether some can be merged.

**Keep tag names lowercase and hyphen-separated.**
Tag names appear in CSV output (`ai_tags` column), SARIF properties, and `@Tag` annotations written by `-apply-tags`. Names with spaces or special characters cause issues in all three contexts.

**Test the taxonomy on a representative sample before adopting it.**
Run MethodAtlas on a sample of ten to twenty test classes whose correct classification you know, and compare the AI output against your expectations. Adjust definitions until the accuracy is satisfactory.

## Verifying taxonomy output

After running with a custom taxonomy, verify that no unexpected tags appear in the output:

```bash
# List all distinct ai_tags values in the CSV output
awk -F',' 'NR > 1 && $5 == "true" {print $7}' security-tests.csv \
  | tr ';' '\n' \
  | sort -u
```

Any tag not defined in your taxonomy file indicates that the AI model generated an out-of-vocabulary tag. This is rare but can happen with models that do not follow prompt instructions precisely. If it occurs, add an explicit instruction to your taxonomy file: *"Return only tags from the list above. Do not invent or add tags not defined in this taxonomy."*

## Further reading

- [Security Taxonomy](taxonomy.md) — built-in tag reference and taxonomy mode selection
- [AI Providers](providers.md) — provider selection affects classification quality
- [Classification Overrides](overrides.md) — correcting individual misclassifications
- [CLI reference — `-ai-taxonomy`](../cli-reference.md)
