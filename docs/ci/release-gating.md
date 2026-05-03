# Release Gating and Regression Prevention

This page describes how to use MethodAtlas as a pre-release gate that
prevents security test coverage from silently degrading between releases.
It covers baseline management, gate condition design, count-gate patterns,
and integration with GitHub branch protection rules and Azure DevOps
environment approvals.

## How the delta mode works

The `-diff` flag compares two MethodAtlas CSV outputs and reports which test
methods were added, removed, or modified between the two runs. It does not
run a scan itself — both input files must be produced by separate MethodAtlas
scan invocations:

```bash
java -jar methodatlas.jar -diff baseline.csv current.csv
```

The output lists each changed method with a change-type indicator:

| Indicator | Meaning |
|-----------|---------|
| `+`       | Method is new in the current scan — added since the baseline |
| `-`       | Method is absent from the current scan — removed or renamed since the baseline |
| `~`       | Method is present in both scans but one or more fields changed |

For `~` entries, a bracketed summary identifies which fields changed:
`source` (class edited), `loc: 5 → 8` (method grew), `security: true → false`
(AI reclassified), `ai_tags` (taxonomy tags changed).

A summary line closes the report:

```text
2 added  ·  1 removed  ·  1 modified  ·  42 unchanged
security-relevant: 5 → 7  (+2)
```

See [Delta Report](../usage-modes/delta.md) for the full output format.

## Gate conditions

Not every change in the delta report warrants blocking the release. The
following table defines recommended gate conditions:

| Delta condition                                                  | Gate action | Rationale |
|------------------------------------------------------------------|-------------|-----------|
| `-` entry for a security-relevant method                         | **Block**   | A security test was removed. Require explicit justification before release. |
| `~` entry with `security: true → false`                          | **Block**   | A method previously classified as security-relevant is now classified otherwise. Regression in coverage. |
| `~` entry with `source` change on a security-relevant method     | **Warn**    | The test was edited; verify the change did not weaken the assertion. |
| `+` entry for a security-relevant method                         | **Allow**   | New security test added. No action required. |
| Count of security-relevant methods decreased                     | **Block**   | Aggregate regression; investigate specific removals. |

A "Block" condition should cause the pipeline job to exit non-zero. A "Warn"
condition should emit a notice and allow the pipeline to continue, but the
finding should appear in the release checklist for human review.

## Count-gate pattern

A count gate fails the release when the absolute number of security-relevant
test methods falls below the baseline count. This is the simplest gate to
implement and requires no baseline CSV — only a stored count:

```bash
# Produce the current scan
java -jar methodatlas.jar \
  -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
  -security-only -content-hash \
  src/test/java > current.csv

# Compare counts
baseline=$(cat .methodatlas-baseline-count 2>/dev/null || echo 0)
current=$(tail -n +2 current.csv | wc -l | tr -d ' ')

echo "Baseline security tests: $baseline"
echo "Current security tests:  $current"

if [ "$current" -lt "$baseline" ]; then
  echo "::error::Security test count dropped from $baseline to $current — release blocked"
  exit 1
fi

# Update the stored count on the default branch
echo "$current" > .methodatlas-baseline-count
```

Commit `.methodatlas-baseline-count` to version control. Each push to the
default branch updates the stored value; each pull request gate compares
against it.

## Shell gate implementation

The following shell fragment implements the blocking conditions using the
`-diff` output and `grep` pattern matching — no additional tooling is required:

```bash
java -jar methodatlas.jar -diff baseline.csv current.csv > delta.txt
cat delta.txt

BLOCKED=0

# Block: security-relevant method removed
if grep -qE "^- " delta.txt; then
  echo "::error::One or more test methods were removed — review delta.txt"
  BLOCKED=1
fi

# Block: security classification regressed
if grep -qE "security: true[[:space:]]*→[[:space:]]*false" delta.txt; then
  echo "::error::Security classification regressed — review delta.txt"
  BLOCKED=1
fi

exit $BLOCKED
```

The `::error::` prefix emits a GitHub Actions workflow error annotation when
the script runs in a GitHub Actions context. Replace it with
`echo "##vso[task.logissue type=error]..."` for Azure DevOps, or with a plain
`echo` for other CI environments.

## Baseline management

The gate compares the current scan against a stored baseline. The baseline
should represent the last known-good state of the security test suite — typically
the most recent commit to the default branch.

### Strategy A: per-release baseline (recommended for regulated environments)

Store a baseline at each release tag. The baseline is the evidence record for
that release; the next release is compared against it.

```bash
# At release time: produce and archive the baseline
java -jar methodatlas.jar \
  -ai -ai-provider <provider> -ai-api-key-env <ENV_VAR> \
  -content-hash -emit-metadata \
  src/test/java > release-$(git describe --tags).csv
```

Archive `release-*.csv` as a release artefact (GitHub Release asset, ADO
artefact, or S3 object). Retrieve the previous release's CSV at the start of
the gate job and pass it as `baseline.csv`.

### Strategy B: rolling default-branch baseline

Store one baseline representing the tip of the default branch. Update it on
every successful push to `main`. Gate every pull request against it.

This strategy requires that the baseline is produced with
[`-ai-cache`](../cli-reference.md#-ai-cache) to limit repeated AI API costs
on unchanged classes.

## GitHub Actions integration

### Branch protection

Configure the `security-gate` job as a required status check in
**Settings → Branches → Branch protection rules** for the `main` branch.
A failed gate job blocks merge until a developer corrects the regression
or a security reviewer documents the accepted risk in the override file.

### Full workflow

```yaml
name: Security gate

on:
  push:
    branches: [main]
  pull_request:

jobs:
  scan:
    name: MethodAtlas scan
    runs-on: ubuntu-latest
    permissions:
      contents: read

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Download MethodAtlas
        run: |
          curl -fsSL -o methodatlas.jar \
            https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar

      - name: Save baseline (on push to main)
        if: github.ref == 'refs/heads/main'
        env:
          AI_API_KEY: ${{ secrets.AI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env AI_API_KEY \
            -content-hash -emit-metadata \
            src/test/java > baseline.csv

          # Store the security test count for the lightweight count gate
          tail -n +2 baseline.csv | wc -l | tr -d ' ' > .methodatlas-baseline-count

      - uses: actions/upload-artifact@v4
        if: github.ref == 'refs/heads/main'
        with:
          name: methodatlas-baseline
          path: |
            baseline.csv
            .methodatlas-baseline-count
          retention-days: 90

  security-gate:
    name: Security regression gate
    runs-on: ubuntu-latest
    needs: scan
    if: github.event_name == 'pull_request'
    permissions:
      contents: read

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Download MethodAtlas
        run: |
          curl -fsSL -o methodatlas.jar \
            https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar

      - name: Download baseline
        uses: actions/download-artifact@v4
        with:
          name: methodatlas-baseline
          path: .

      - name: Run current scan
        env:
          AI_API_KEY: ${{ secrets.AI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env AI_API_KEY \
            -content-hash \
            src/test/java > current.csv

      - name: Count gate
        run: |
          baseline_count=$(cat .methodatlas-baseline-count 2>/dev/null || echo 0)
          current_count=$(tail -n +2 current.csv | wc -l | tr -d ' ')
          echo "Baseline security tests: $baseline_count"
          echo "Current security tests:  $current_count"
          if [ "$current_count" -lt "$baseline_count" ]; then
            echo "::error::Security test count dropped from $baseline_count to $current_count"
            exit 1
          fi

      - name: Evaluate delta
        run: |
          java -jar methodatlas.jar -diff baseline.csv current.csv > delta.txt
          cat delta.txt

          BLOCKED=0

          if grep -qE "^- " delta.txt; then
            echo "::error::Test methods were removed — review delta.txt"
            BLOCKED=1
          fi

          if grep -qE "security: true[[:space:]]*→[[:space:]]*false" delta.txt; then
            echo "::error::Security classification regressed — review delta.txt"
            BLOCKED=1
          fi

          exit $BLOCKED

      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: methodatlas-delta
          path: delta.txt
          retention-days: 30
```

## Azure DevOps integration

### Environment protection gate

In Azure DevOps YAML pipelines, deploy to a protected environment to
implement a pre-release approval gate. If the `SecurityGate` stage exits
non-zero, the deployment to the `production` environment does not proceed.

```yaml
stages:
  - stage: SecurityGate
    displayName: Security regression gate
    jobs:
      - job: Gate
        pool:
          vmImage: ubuntu-latest
        steps:
          - task: JavaToolInstaller@0
            inputs:
              versionSpec: '21'
              jdkArchitectureOption: x64
              jdkSourceOption: PreInstalled

          - script: |
              curl -fsSL -o methodatlas.jar \
                https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
            displayName: Download MethodAtlas

          - task: DownloadPipelineArtifact@2
            inputs:
              artifactName: methodatlas-baseline
              targetPath: $(Build.SourcesDirectory)

          - script: |
              java -jar methodatlas.jar \
                -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
                -content-hash \
                src/test/java > current.csv

              # Count gate
              baseline_count=$(cat .methodatlas-baseline-count 2>/dev/null || echo 0)
              current_count=$(tail -n +2 current.csv | wc -l | tr -d ' ')
              echo "Baseline: $baseline_count  Current: $current_count"
              if [ "$current_count" -lt "$baseline_count" ]; then
                echo "##vso[task.logissue type=error]Security test count dropped from $baseline_count to $current_count"
                exit 1
              fi

              # Delta gate
              java -jar methodatlas.jar \
                -diff baseline.csv current.csv > delta.txt
              cat delta.txt

              BLOCKED=0
              grep -qE "^- " delta.txt && BLOCKED=1
              grep -qE "security: true[[:space:]]*→[[:space:]]*false" delta.txt && BLOCKED=1
              exit $BLOCKED
            displayName: Evaluate delta
            env:
              OPENAI_API_KEY: $(openaiApiKey)

  - stage: Deploy
    displayName: Deploy to production
    dependsOn: SecurityGate
    jobs:
      - deployment: Production
        environment: production   # requires approval in ADO Environments UI
        strategy:
          runOnce:
            deploy:
              steps:
                - script: echo "Deploy steps here"
```

Configure the `production` environment under **Pipelines → Environments** in
the Azure DevOps UI to require one or more manual approvals before the
deployment job runs. This creates a two-layer gate: the automated delta check
plus a human sign-off.

## Handling justified removals

When a security-relevant test is legitimately removed — because the feature it
tested was removed, or the test was replaced by a better one — the gate will
block until the removal is documented.

The recommended process:

1. Remove the test from source.
2. Run the gate locally; observe the `-` entry in the delta.
3. If the removal is justified, add a `securityRelevant: false` entry to the
   [override file](../ai/overrides.md) for the removed method, with a `note`
   recording the reviewer's name, date, and rationale.
4. The override entry silences the AI-side of the gate. For CI gates that
   check raw delta output, the `-` entry remains; the development team
   acknowledges it in the PR description.

Storing the justification in the override file creates a durable, version-
controlled record that auditors can examine.

## Further reading

- [Delta Report](../usage-modes/delta.md) — complete output format and field reference
- [Classification Overrides](../ai/overrides.md) — documenting accepted risks
- [GitHub Actions](github-actions.md) — full workflow reference
- [Azure DevOps](azure-devops.md) — full pipeline reference
- [GitHub branch protection rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
- [Azure DevOps environment approvals and checks](https://learn.microsoft.com/en-us/azure/devops/pipelines/process/approvals)
