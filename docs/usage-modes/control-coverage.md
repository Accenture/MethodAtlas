# Control-Coverage Matrix

`-emit-coverage` produces a JSON document that maps every requirement ID in a user-authored compliance mapping to the test methods that provide evidence of coverage. It is the file that GRC (Governance, Risk, Compliance) tools want to import; it is also the file an auditor reads to find the gaps.

## Overview

Compliance teams already maintain a spreadsheet that connects each control in their target framework to the tests that satisfy it. The spreadsheet drifts: tests are renamed, controls are reinterpreted, columns gain custom semantics, and the spreadsheet stops matching the code.

`-emit-coverage` is the fix: every scan re-derives the matrix from the live source tree using the same `@Tag` annotations and AI classifications that already drive other MethodAtlas output. The tool records what the user-authored mapping says; it does not pass judgement on compliance claims. The mapping file — `-coverage-mapping <path>` — is the contract between your team and your auditor.

**Design principle.** The mapping is data, not code. It is owned by the team that owns the audit. The tool's job is to project facts about test source onto that mapping; it ships no built-in interpretation.

## CLI flags

| Flag | Purpose |
|------|---------|
| `-emit-coverage` | Opt-in to coverage emission. **Required.** Without it nothing is written. |
| `-coverage-mapping <path>` | User-authored mapping JSON. **Required when `-emit-coverage` is present.** Absence causes exit code `2` with a helpful stderr message pointing at the reference template. |
| `-coverage-file <path>` | Override the default output path (`controls-coverage.json` in the current working directory). |

A coverage-write failure logs a `WARNING` via `java.util.logging` and lets the scan keep its original exit code. A mapping-load failure is fatal (exit `2`) — the mapping is required input, not optional decoration.

## Mapping file format

```json
{
  "schemaVersion": "1",
  "framework": "ASVS",
  "frameworkVersion": "4.0",
  "tagToControls": {
    "auth": [
      { "id": "2.1.1", "chapter": "V2", "chapterTitle": "Authentication Verification Requirements" }
    ],
    "crypto": [
      { "id": "6.4.1", "chapter": "V6", "chapterTitle": "Stored Cryptography Verification Requirements" }
    ]
  }
}
```

Constraints validated at load time (failures exit `2`):

| Field | Type | Constraint |
|-------|------|------------|
| `schemaVersion` | string | Must equal `"1"`. |
| `framework` | string | Non-blank. Upper-cased to form the key prefix in the report. |
| `frameworkVersion` | string | Non-blank. Recorded as-is. |
| `tagToControls` | object | Non-empty. Keys are taxonomy tag names. Each value is a JSON array of control entries. |
| `tagToControls[tag][i].id` | string | Non-blank. The bare requirement ID. |
| `tagToControls[tag][i].chapter` | string \| null | Optional. Omitted from the output when null/absent. |
| `tagToControls[tag][i].chapterTitle` | string \| null | Optional. Omitted from the output when null/absent. |

Unknown top-level fields are silently ignored for forward compatibility. The reference template uses `_comment`, `_sourceDocument`, and `notes` for human-readable provenance; these survive a load/parse round-trip without affecting validation.

## Output file format

```json
{
  "schemaVersion": "1",
  "generatedUtc": "2026-05-29T14:03:11.042Z",
  "methodAtlasVersion": "3.4.0",
  "framework": "ASVS",
  "frameworkVersion": "4.0",
  "mappingSource": "/abs/path/to/my-mapping.json",
  "coverage": {
    "ASVS-4.1.1": {
      "chapter": "V4",
      "chapterTitle": "Access Control Verification Requirements",
      "tests": [
        {
          "fqcn": "com.acme.AccessControlServiceTest",
          "method": "denyAnonymousUser",
          "tags": ["access-control"],
          "tagSource": "source",
          "confidence": 1.0
        }
      ]
    }
  },
  "gaps": ["ASVS-4.1.2", "ASVS-4.2.1"],
  "statistics": {
    "totalMappedControls": 47,
    "coveredControls": 1,
    "uncoveredControls": 46,
    "coveragePercent": 2.13
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| `coverage` | object | Insertion-ordered (lexicographically by control key). Only contains covered controls. Each entry's `tests` array is non-empty. |
| `gaps` | string array | Sorted lexicographically. Each entry is a control key with zero covering tests. |
| `statistics.coveragePercent` | number | Rounded to two decimal places via `Math.round(ratio * 10_000) / 100.0`. `0.0` when the mapping is empty. |
| `tagSource` | string | One of `"source"`, `"ai"`, `"both"`. See the section below. |
| `confidence` | number | `1.0` whenever a source annotation contributes; otherwise the AI confidence score. |

Null optional fields (chapter, chapterTitle, displayName) are omitted from the JSON entirely via Jackson `NON_NULL`.

## GRC tool import

The output is direct-import-ready for most modern GRC platforms. The key shape design is two-level: top-level identification metadata (framework, framework version, mapping source), and a `coverage` object whose values are flat enough to map to evidence columns one-to-one.

| GRC platform | Import path |
|--------------|-------------|
| RSA Archer | REST JSON import. Map `coverage.<id>.tests[]` to "Evidence records" associated with the control of ID `<id>`. |
| ServiceNow GRC | Use the table import wizard against the flat-table derivation (jq one-liner below). |
| OneTrust GRC | Custom evidence importer; the top-level `framework` and `frameworkVersion` provide framework selection automatically. |

For tools that prefer a flat row format (one test per row, control denormalised):

```bash
jq '[.coverage | to_entries[]
     | .key as $ctrl | .value.tests[]
     | {control: $ctrl, fqcn: .fqcn, method: .method,
        displayName: .displayName, tagSource: .tagSource,
        confidence: .confidence}]' controls-coverage.json
```

The fields present on every flat row (`control`, `fqcn`, `method`, `displayName`, `tagSource`, `confidence`) map directly to the columns most GRC platforms expose for evidence records.

## Authoring a mapping file

### Starting from your taxonomy

For each tag in your taxonomy:

1. Locate the chapter(s) in your compliance framework that the tag's definition addresses.
2. Select the specific requirement IDs in those chapters that a test method can plausibly cover.
3. Record one entry per selected ID with its bare `id`, plus optionally `chapter` and `chapterTitle` for human-readable context.

> The reference template warns: *"Review and adapt to your project's compliance scope before use."* The tool ships no opinion about which ASVS controls a given test should satisfy; the file is data that lives next to your tests, reviewed by the same people who review your test code.

### Worked example: MethodAtlas built-in taxonomy mapped to ASVS 4.0

The template at [`docs/examples/asvs4-mapping.json`](../examples/asvs4-mapping.json) was derived end-to-end from two existing MethodAtlas documents. Walking through the derivation explains the design choices an author needs to make for any taxonomy.

#### Step 1 — quote the tag definition

From [`docs/ai/taxonomy.md`](../ai/taxonomy.md):

> `auth` — Authentication: identity verification, login, token handling.

#### Step 2 — locate the chapter mapping

From [`docs/concepts/asvs-mapping.md`](../concepts/asvs-mapping.md):

> The `auth` tag covers ASVS V2 Authentication Verification Requirements and V3 Session Management Verification Requirements. Specific IDs: 2.1.1, 2.1.7, 2.2.1, 2.4.1, 2.6.1, 3.2.1, 3.3.1, 3.3.2, 3.4.1, 3.7.1.

#### Step 3 — render the JSON fragment

```json
"auth": [
  { "id": "2.1.1", "chapter": "V2", "chapterTitle": "Authentication Verification Requirements" },
  { "id": "2.1.7", "chapter": "V2", "chapterTitle": "Authentication Verification Requirements" },
  { "id": "2.2.1", "chapter": "V2", "chapterTitle": "Authentication Verification Requirements" },
  { "id": "2.4.1", "chapter": "V2", "chapterTitle": "Authentication Verification Requirements" },
  { "id": "2.6.1", "chapter": "V2", "chapterTitle": "Authentication Verification Requirements" },
  { "id": "3.2.1", "chapter": "V3", "chapterTitle": "Session Management Verification Requirements" },
  { "id": "3.3.1", "chapter": "V3", "chapterTitle": "Session Management Verification Requirements" },
  { "id": "3.3.2", "chapter": "V3", "chapterTitle": "Session Management Verification Requirements" },
  { "id": "3.4.1", "chapter": "V3", "chapterTitle": "Session Management Verification Requirements" },
  { "id": "3.7.1", "chapter": "V3", "chapterTitle": "Session Management Verification Requirements" }
]
```

#### Step 4 — explain the inclusions and exclusions

Not every V2/V3 requirement is in the mapping. ASVS V2 alone contains dozens of IDs across credential storage, password recovery, multi-factor authentication, and federation. The template only includes IDs where a test method can be expected to demonstrate the behaviour the control requires:

| ID | Included? | Why |
|----|-----------|-----|
| 2.1.1 — Verify user-set passwords are at least 12 characters | ✓ | Tests routinely verify password length validation logic. |
| 2.6.1 — Look-up secrets (OTP / backup codes) | ✗ from this template | Only applicable to projects that implement OTP/backup-code flows. The template author should add it back when authoring for such a project. |

The same rule applies for every tag: pick what your tests can plausibly demonstrate. A control listed in the mapping that no test can ever satisfy will appear permanently in `gaps`, which is acceptable but produces noise.

#### Other tags

The same derivation produced the rest of the template:

- `"injection"` — a single chapter (V5 Validation, Sanitization and Encoding) with five injection-specific IDs (5.3.4, 5.3.5, 5.3.8, 5.3.10, 5.3.14). Single-chapter scope is the simplest possible mapping.
- `"crypto"` — two chapters apply, because cryptographic controls span both **stored** material (V6 Stored Cryptography Verification Requirements) and **transit** material (V9 Communications Verification Requirements). Both must be represented because a test of TLS certificate validation belongs to V9, but a test of password hashing belongs to V6, and the same `crypto` tag covers both.

The tags `"security"` and `"owasp"` are deliberately absent. `"security"` is an umbrella marker — it carries no information about which control is being tested. `"owasp"` is a multi-chapter signal that promises everything and certifies nothing. For GRC output, **specificity matters**: a claim that covers everything covers nothing auditable. Authors who want their `security`-tagged tests to surface in the report should also tag the test with a specific concern (`auth`, `crypto`, …).

#### Full template

The complete reference template, including the chapter labels for every entry above, is committed at [`docs/examples/asvs4-mapping.json`](../examples/asvs4-mapping.json). It is not loaded automatically — copy it into your repository and adapt it to your scope before pointing `-coverage-mapping` at it.

### Adapting for a custom taxonomy

A fintech team using the custom taxonomy `pci-cardholder-data`, `pci-key-management`, `pci-network-segmentation` (defined in [`docs/ai/custom-taxonomy.md`](../ai/custom-taxonomy.md)) might author a mapping like this:

```json
{
  "schemaVersion": "1",
  "framework": "PCI-DSS",
  "frameworkVersion": "4.0",
  "tagToControls": {
    "pci-cardholder-data": [
      { "id": "3.5.1", "chapter": "Requirement 3", "chapterTitle": "Protect Stored Account Data" }
    ],
    "pci-key-management": [
      { "id": "3.6.1", "chapter": "Requirement 3", "chapterTitle": "Protect Stored Account Data" }
    ],
    "pci-network-segmentation": [
      { "id": "1.4.1", "chapter": "Requirement 1", "chapterTitle": "Install and Maintain Network Security Controls" }
    ]
  }
}
```

The output is keyed by `PCI-DSS-3.5.1`, `PCI-DSS-3.6.1`, `PCI-DSS-1.4.1`. The format is framework-agnostic.

### Authoring checklist

1. List every tag in your taxonomy file.
2. For each tag, identify the compliance chapter(s) it addresses.
3. Select only requirement IDs where your tests directly verify the required behaviour — not incidental coverage.
4. Check the `gaps` output after the first run; treat gaps as a backlog.
5. Have your compliance team or auditor review the mapping file.
6. Commit the mapping file to version control alongside your tests.

## Confidence and tag sources

The `tagSource` field carries the provenance of every coverage claim:

| `tagSource` | Meaning | Audit-evidence strength |
|-------------|---------|-------------------------|
| `"source"` | A human added `@Tag("X")` (or equivalent) to the test method. | Strongest — a human intentionally claimed this control was covered. `confidence` is always `1.0`. |
| `"both"` | A human annotation **and** an AI classification both produced a mappable tag. | Equivalent to `"source"` for audit purposes — the human annotation pins `confidence` to `1.0`; the AI agreement adds defensibility. |
| `"ai"` | Only the AI suggested the tag. | Weakest — depends on AI quality. `confidence` is the AI's reported confidence; tighten with `-min-confidence 0.8` (or higher) for GRC use. |

For GRC submissions, set `-min-confidence 0.8` at minimum and prefer source-annotated evidence wherever the team is willing to maintain the annotations.

## Known limitations

- **No semantic verification.** The tool cannot verify that a test actually exercises the behaviour a control requires — only that it carries a tag the mapping declares relevant. That judgement belongs to the mapping file author and (ultimately) the auditor.
- **Mapping file is required.** No built-in mapping ships with the tool. Custom-taxonomy users must author a matching mapping; the reference template only covers the built-in taxonomy.
- **AI-sourced coverage is only as reliable as the classification.** Use `-ai-cache` for reproducible results across runs; pair with `-emit-receipt` (see [Reproducibility Receipts](reproducibility-receipts.md)) to demonstrate that the inputs to two runs were identical.
- **Tags absent from the mapping are silently skipped** at record time. This is deliberate — a tag the team has not yet decided to map shouldn't flood the report — but it means an introspection of the mapping file is needed to understand what was excluded.
