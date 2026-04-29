# Remote override sources

In larger organisations a dedicated security or risk team is responsible for classification decisions — which test methods are security-relevant, which tags apply, and which rationales are recorded for an audit. This page describes four strategies for delivering the override file from a remote location into a GitHub Actions workflow, with a practical assessment of each.

## When to use

Use a remote override source when the security team that owns classification decisions works in a different repository from the development teams whose tests are being scanned. If the override file lives in the same repository as the tests, a plain local-file path in the reusable workflow is sufficient — see [Classification Overrides](overrides.md#workflow-integration).

## Strategy comparison

| Strategy                                                                       | Who controls the file  | Auth mechanism                     | Complexity   | Suitable when                                                         |
|--------------------------------------------------------------------------------|------------------------|------------------------------------|--------------|-----------------------------------------------------------------------|
| [Local file in dev repo](#local-file-simplified)                               | Development team       | Git write access                   | Low          | Small team; single repo; security reviewer has repo access            |
| [Checkout of security repo](#checkout-of-security-repo-recommended)           | Security team          | Fine-grained PAT or GitHub App     | Low–Medium   | Dedicated security team; GitHub-hosted repos; recommended default     |
| [HTTPS download](#https-download-artifact-server)                              | Security team          | Bearer token                       | Medium       | Security file hosted on Artifactory, S3, Azure Blob, or internal web  |
| [Reusable workflow from security team](#reusable-workflow-from-security-team)  | Security team          | `GITHUB_TOKEN` / GitHub App        | High         | Security team wants full control of distribution logic                |

All four strategies are supported by the bundled reusable workflow (`.github/workflows/methodatlas-analysis.yml`). The checkout-of-security-repo and local-file paths are built in. HTTPS download and the reusable-workflow pattern require adapting the calling workflow — see the sections below.

## Local file (simplified)

Store the override file in the development repository itself and pass its path via the `override-file` input when calling the reusable workflow:

```yaml
# .github/workflows/pages.yml (excerpt)
jobs:
  analyze:
    uses: ./.github/workflows/methodatlas-analysis.yml
    with:
      override-file: .methodatlas-overrides.yaml
    permissions:
      contents: read
      security-events: write
      models: read
```

**Positives**

- Zero additional setup; no secrets or external access required.
- Override changes are reviewed through the normal PR process in the same repo.
- Diff in the override file is the audit trail.

**Negatives**

- Any developer with write access to the repo can modify classifications.
- Security decisions are scattered across many repos — no central view.
- A malicious or mistaken PR can silently suppress a security finding.

**Verdict:** Suitable for small teams where the security reviewer is also a contributor to the development repository. Not recommended when a separate security or risk team should be the sole authority over override decisions.

## Checkout of security repo (recommended)

The security team maintains a dedicated repository (e.g. `acme-corp/security-overrides`) containing override files for all projects. The reusable workflow checks out that repository using a short-lived token before running MethodAtlas.

### Setup

1. The security team creates `acme-corp/security-overrides` and commits an override file, for example `methodatlas-overrides.yaml`.

2. Create a **fine-grained PAT** (GitHub → Settings → Developer settings → Personal access tokens → Fine-grained) with:
   - Resource owner: `acme-corp`
   - Repository access: `acme-corp/security-overrides` only
   - Permissions: `Contents: Read`

   Store the token as an **organisation-level secret** named `SECURITY_OVERRIDES_TOKEN` so that all development repositories can use it without each team managing their own copy.

3. In the development repository's calling workflow, pass the new inputs and inherit the secret:

```yaml
# .github/workflows/pages.yml (excerpt)
jobs:
  analyze:
    uses: ./.github/workflows/methodatlas-analysis.yml
    with:
      security-overrides-repo: acme-corp/security-overrides
      security-overrides-path: methodatlas-overrides.yaml   # default; omit if unchanged
      security-overrides-ref: v1.3.0                        # pin to a release tag
    secrets:
      SECURITY_OVERRIDES_TOKEN: ${{ secrets.SECURITY_OVERRIDES_TOKEN }}
    permissions:
      contents: read
      security-events: write
      models: read
```

The workflow performs a shallow, sparse checkout (only the single override file is transferred) and passes `-override-file` to MethodAtlas automatically. If the file is absent or the secret is not set, the workflow continues without an override file and does not fail.

**Positives**

- Security team has exclusive write access to the override file.
- Development teams cannot alter classifications without a PR to the security repository, which requires approval from the security team.
- Pin to a tag (`security-overrides-ref: v1.3.0`) for reproducible, auditable runs — a build at any point in time can be traced to an exact override revision.
- Organisation-level secret means zero per-repo secret management for development teams.
- Sparse checkout transfers only the single file — negligible bandwidth and no unrelated content is materialised on the runner.

**Negatives**

- Requires a fine-grained PAT (or GitHub App token — see below) and an organisation-level secret.
- PATs expire; the security team must rotate them and update the org secret before expiry.

**PAT vs. GitHub App token:** A fine-grained PAT is tied to a user account and expires. A GitHub App installation token is machine-scoped, auto-rotates, and provides a richer audit trail. Prefer GitHub App tokens for production deployments with more than ~20 consuming repositories.

## HTTPS download (artifact server)

If the security team hosts the override file on an internal server (Artifactory, Nexus, S3, Azure Blob Storage, or a plain HTTPS endpoint), download it in a custom step before running the reusable workflow — or replace the reusable workflow with a custom workflow that includes the download inline.

```yaml
# Custom security-scan.yml — does NOT use the reusable workflow
jobs:
  scan:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write

    steps:
      - uses: actions/checkout@v4

      - name: Download MethodAtlas
        run: |
          VERSION=$(curl -fsSL https://api.github.com/repos/Accenture/MethodAtlas/releases/latest \
                    | jq -r '.tag_name | ltrimstr("release@")')
          curl -fsSL \
            "https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas-${VERSION}.zip" \
            -o methodatlas.zip
          unzip -q methodatlas.zip
          echo "METHODATLAS=$(pwd)/methodatlas-${VERSION}/bin/methodatlas" >> "$GITHUB_ENV"

      - name: Download override file
        run: |
          curl -fsSL \
            -H "Authorization: Bearer $OVERRIDES_TOKEN" \
            "$OVERRIDES_URL" \
            -o .methodatlas-overrides.yaml
          # Verify integrity — never skip this step
          echo "$EXPECTED_SHA256  .methodatlas-overrides.yaml" | sha256sum -c -
        env:
          OVERRIDES_TOKEN: ${{ secrets.OVERRIDES_TOKEN }}
          OVERRIDES_URL: ${{ vars.OVERRIDES_URL }}
          EXPECTED_SHA256: ${{ vars.OVERRIDES_SHA256 }}

      - name: Run MethodAtlas
        run: |
          OVERRIDE_ARGS=()
          if [ -f .methodatlas-overrides.yaml ]; then
            OVERRIDE_ARGS=("-override-file" ".methodatlas-overrides.yaml")
          fi

          "$METHODATLAS" \
            -sarif \
            -ai \
            -ai-provider github_models \
            -ai-model gpt-4o-mini \
            -ai-api-key-env GITHUB_TOKEN \
            "${OVERRIDE_ARGS[@]}" \
            src/test/java \
            > methodatlas.sarif
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

      - uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: methodatlas.sarif
          category: security-tests
```

The checksum (`OVERRIDES_SHA256`) should be published alongside the file by the security team and stored as a repository or organisation variable. Update it whenever the override file is released.

**Positives**

- Works with any HTTPS-accessible storage — no GitHub dependency.
- Suitable for hybrid environments where the security team uses a non-GitHub system (Artifactory, internal wiki, S3).
- Token scope can be tightly restricted to a single file or bucket prefix.

**Negatives**

- **Checksum verification is mandatory** — without it, a compromised storage bucket or a misconfigured URL silently feeds a tampered override file to MethodAtlas.
- The `OVERRIDES_SHA256` variable must be updated manually on every release of the override file; forgetting breaks the build.
- More moving parts than the checkout approach: token, URL variable, checksum variable, and the download step all need to be kept in sync.

**Verdict:** Prefer the checkout approach when all repositories are on GitHub. Use HTTPS download only when the security team's toolchain is not GitHub-based.

## Reusable workflow from security team

The security team publishes a reusable workflow in their own repository that encapsulates the entire fetch-and-pass logic. Development teams call it as a job dependency:

```yaml
# .github/workflows/security-scan.yml in the development repo
jobs:
  # Security team's workflow fetches the override and exposes it as an artifact
  fetch-overrides:
    uses: acme-corp/security-overrides/.github/workflows/publish-overrides.yml@v2
    secrets: inherit

  # MethodAtlas run depends on the fetched artifact
  analyze:
    needs: fetch-overrides
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Download override artifact
        uses: actions/download-artifact@v4
        with:
          name: methodatlas-overrides
          path: .

      - name: Run MethodAtlas
        # ... standard invocation with -override-file .methodatlas-overrides.yaml
```

The security team's `publish-overrides.yml` controls which override file is served, can apply per-team logic (different files for frontend vs. backend teams), and uploads the result as a workflow artifact consumed by the next job.

**Positives**

- Security team has complete control over what is delivered and to whom.
- Business logic (team routing, environment-specific files, freshness checks) lives entirely in the security repo, invisible to development teams.
- Development teams need zero knowledge of where or how overrides are stored.

**Negatives**

- Highest complexity: two separate workflow files must be maintained across two repositories.
- Artifact sharing between jobs adds latency and has a 30-day retention limit (artifacts can be consumed only within the same workflow run).
- `secrets: inherit` grants the called workflow access to all calling workflow's secrets — review whether this is acceptable in your threat model.
- Debugging cross-repo workflow failures is harder than debugging a single file.

**Verdict:** Reserve for organisations that have a mature DevSecOps practice and where the security team already owns and operates shared reusable workflows. For most teams the checkout approach delivers the same security guarantees with significantly lower operational complexity.

## Choosing a strategy

- Single team, one repo, security reviewer is a developer → **Local file**
- Dedicated security team, GitHub-hosted repos → **Checkout of security repo** (recommended default)
- Security team uses non-GitHub artifact storage → **HTTPS download**
- Security team operates shared reusable workflows → **Reusable workflow**

See [Classification Overrides](overrides.md) for the override file format and [CLI reference — `-override-file`](../cli-reference.md#-override-file) for the flag description.
