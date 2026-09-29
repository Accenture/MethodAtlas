# Attestation Manifest (`-attest`)

The `-attest` flag generates a compact JSON manifest after an AI-classified scan. The manifest records every security-relevant test method with its content hash, AI taxonomy tags, and the commit SHA of the current HEAD. Sign it externally with `cosign` or GPG and attach it to a release as a tamper-evident, verifiable record of which security tests existed at a specific commit.

## Why it exists

A signed attestation manifest fulfils the "evidence of testing" requirement in ISO 27001 clause A.14.2.8, SOC 2 CC8.1, and PCI-DSS Requirement 6 without requiring auditors to access the full CI log. It answers: *"Which security-relevant tests existed at commit `abc1234`, and can you prove the answer was not altered after the fact?"*

No standard CI/CD tool produces this artifact automatically. JaCoCo proves line coverage; SAST proves findings; neither produces a per-test, commit-anchored, signable record.

## Quick start

```bash
./methodatlas -ai -attest src/test/java
```

The manifest is written to `security-tests.attestation.json` in the current working directory.

## Options

| Flag | Effect | Default |
|------|--------|---------|
| `-attest` | Enable attestation manifest generation | Off |
| `-attest-file <path>` | Override output path | `security-tests.attestation.json` in CWD |
| `-min-confidence <n>` | Exclude methods below confidence threshold | `0.0` |

## Manifest format

```json
{
  "schemaVersion": "1",
  "generatedUtc": "2026-09-29T12:34:56.789Z",
  "methodAtlasVersion": "3.0.0",
  "commitSha": "abc1234...",
  "scanRoots": ["src/test/java"],
  "totalMethods": 1234,
  "securityMethods": 87,
  "entries": [
    {
      "fqcn": "com.acme.AuthTest",
      "method": "testLogin_validCredentials",
      "contentHash": "a1b2c3...",
      "aiTags": ["auth", "access-control"]
    }
  ]
}
```

Fields:

- **schemaVersion** — `"1"`; increment when the format changes
- **generatedUtc** — ISO-8601 instant at manifest creation time
- **methodAtlasVersion** — tool version string from the JAR manifest, or `"dev"`
- **commitSha** — resolved from `GITHUB_SHA`, `CI_COMMIT_SHA`, `GIT_COMMIT`, `BUILD_SOURCEVERSION`, or `BITBUCKET_COMMIT`; `"unknown"` when none is set
- **scanRoots** — the paths passed to the scan
- **totalMethods** — all methods seen (security-relevant or not)
- **securityMethods** — count of entries in the `entries` array
- **entries** — one object per security-relevant method; `contentHash` is `null` when `-content-hash` was not used

## Commit SHA resolution

MethodAtlas resolves the commit SHA from environment variables in this order:

1. `GITHUB_SHA` (GitHub Actions)
2. `CI_COMMIT_SHA` (GitLab CI)
3. `GIT_COMMIT` (Jenkins)
4. `BUILD_SOURCEVERSION` (Azure DevOps)
5. `BITBUCKET_COMMIT` (Bitbucket Pipelines)

If none is set, the field is `"unknown"`. To set it explicitly when running locally:

```bash
GIT_COMMIT=$(git rev-parse HEAD) ./methodatlas -ai -attest src/test/java
```

## Signing the manifest

### With cosign (keyless, OIDC)

```bash
cosign sign-blob security-tests.attestation.json \
  --bundle security-tests.attestation.bundle
```

Verify later:

```bash
cosign verify-blob security-tests.attestation.json \
  --bundle security-tests.attestation.bundle \
  --certificate-identity <your-identity> \
  --certificate-oidc-issuer <your-issuer>
```

### With GPG

```bash
gpg --detach-sign --armor security-tests.attestation.json
```

Verify:

```bash
gpg --verify security-tests.attestation.json.asc security-tests.attestation.json
```

## CI/CD integration

```yaml
# GitHub Actions
- name: Scan and attest
  run: |
    ./methodatlas -ai -attest -content-hash src/test/java

- name: Sign attestation
  run: |
    cosign sign-blob security-tests.attestation.json \
      --bundle security-tests.attestation.bundle

- name: Upload attestation
  uses: actions/upload-artifact@v4
  with:
    name: security-attestation
    path: |
      security-tests.attestation.json
      security-tests.attestation.bundle
    retention-days: 365
```

Attach the manifest and its signature bundle as GitHub Release assets so auditors can download and verify them independent of CI access.

## Using with `-content-hash`

Running with `-content-hash` populates the `contentHash` field in each entry. This makes the manifest more tamper-evident because any modification to a test class source file changes its hash, invalidating the entry.

```bash
./methodatlas -ai -attest -content-hash src/test/java
```

## Combining with the evidence report

Generate both artifacts in a single scan:

```bash
./methodatlas -ai -attest -evidence-report \
  -attest-file security-attestation.json \
  -evidence-report-file security-evidence-report.md \
  src/test/java
```

See [Security evidence report](evidence-report.md) for the human-readable complement to this machine-verifiable manifest.

## YAML configuration

```yaml
# methodatlas.yaml
attest: true
attestFile: security-tests.attestation.json
minConfidence: 0.8
```
