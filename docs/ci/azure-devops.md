# Azure DevOps Pipelines

This page describes how to integrate MethodAtlas into an Azure DevOps (ADO)
YAML pipeline. The techniques can be used individually or combined:

- **SARIF upload to Advanced Security** — findings appear in the ADO pull request security widget
- **AI result caching** — skips re-classification of unchanged test classes between runs
- **Security test count gate** — pipeline fails when security test coverage drops
- **Release gate** — blocks deployment when security test regression is detected

## Prerequisites

| Requirement                         | Details |
|-------------------------------------|---------|
| Java runtime                        | Java 21 or later; available as `PreInstalled` on `ubuntu-latest` agents, or configured via `JavaToolInstaller@0` |
| MethodAtlas                         | Downloaded at job start from the GitHub release; no separate installation step |
| AI provider API key                 | Stored as a masked pipeline variable or in a variable group in the ADO Library |
| GitHub Advanced Security for ADO    | Required only for SARIF upload to the security dashboard; not required for CSV output or count gating |

## Variable group configuration

Store the AI provider API key in a variable group to avoid repeating it in
every pipeline file. In the ADO UI navigate to
**Pipelines → Library → Variable groups** and create a group named
`methodatlas-secrets` containing:

| Variable          | Value                  | Secret |
|-------------------|------------------------|--------|
| `openaiApiKey`    | Your OpenAI API key    | Yes — mark as secret |

Reference the group in any pipeline that needs it:

```yaml
variables:
  - group: methodatlas-secrets
```

Use a different variable name and the `-ai-provider` flag if you use a
provider other than OpenAI. See [AI Providers](../ai/providers.md).

## Minimal pipeline: static inventory

The following pipeline requires no AI provider and produces a CSV inventory of
all test methods with their structural metadata. No secrets or variable
group are required.

```yaml
trigger:
  - main

pool:
  vmImage: ubuntu-latest

steps:
  - task: JavaToolInstaller@0
    inputs:
      versionSpec: '21'
      jdkArchitectureOption: x64
      jdkSourceOption: PreInstalled
    displayName: Set up Java 21

  - script: |
      curl -fsSL -o methodatlas.jar \
        https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
    displayName: Download MethodAtlas

  - script: |
      java -jar methodatlas.jar src/test/java > inventory.csv
    displayName: Run MethodAtlas — static inventory

  - task: PublishBuildArtifacts@1
    inputs:
      pathToPublish: inventory.csv
      artifactName: methodatlas-inventory
    displayName: Publish inventory
```

## AI-enriched scan with SARIF output

To produce a SARIF file for upload to GitHub Advanced Security for Azure
DevOps (GHAzDO), first enable Advanced Security in your ADO organisation
under **Organisation settings → Security → Advanced Security**.

```yaml
variables:
  - group: methodatlas-secrets

steps:
  - task: JavaToolInstaller@0
    inputs:
      versionSpec: '21'
      jdkArchitectureOption: x64
      jdkSourceOption: PreInstalled
    displayName: Set up Java 21

  - script: |
      curl -fsSL -o methodatlas.jar \
        https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
    displayName: Download MethodAtlas

  - script: |
      java -jar methodatlas.jar \
        -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
        -sarif -security-only \
        -content-hash \
        src/test/java \
        > $(Build.SourcesDirectory)/methodatlas.sarif
    displayName: Run MethodAtlas — SARIF
    env:
      OPENAI_API_KEY: $(openaiApiKey)

  - task: PublishBuildArtifacts@1
    inputs:
      pathToPublish: $(Build.SourcesDirectory)/methodatlas.sarif
      artifactName: methodatlas-sarif
    displayName: Publish SARIF artefact
```

!!! note "SARIF upload to the ADO security dashboard"
    Uploading SARIF results to the GitHub Advanced Security for Azure DevOps
    dashboard requires the `AdvancedSecurity-Publish@1` task, which is part
    of the GHAzDO extension. Consult the
    [Microsoft documentation](https://learn.microsoft.com/en-us/azure/devops/repos/security/github-advanced-security-sarif)
    for the current task version and required permissions, as these change
    with product updates.

## Caching AI results across runs

Use the `Cache@2` task to persist the MethodAtlas result file across pipeline
runs. The cache key is computed from the test source file hashes, so the cache
is invalidated automatically when test sources change:

```yaml
variables:
  - group: methodatlas-secrets

steps:
  - task: JavaToolInstaller@0
    inputs:
      versionSpec: '21'
      jdkArchitectureOption: x64
      jdkSourceOption: PreInstalled
    displayName: Set up Java 21

  - script: |
      curl -fsSL -o methodatlas.jar \
        https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
    displayName: Download MethodAtlas

  - task: Cache@2
    inputs:
      key: 'methodatlas | "$(Agent.OS)" | $(Build.SourcesDirectory)/src/test/java/**/*.java'
      restoreKeys: |
        methodatlas | "$(Agent.OS)"
      path: $(Build.SourcesDirectory)/.methodatlas-cache.csv
    displayName: Restore MethodAtlas AI cache

  - script: |
      CACHE_ARG=""
      if [ -f .methodatlas-cache.csv ]; then
        CACHE_ARG="-ai-cache .methodatlas-cache.csv"
      fi

      # Pass 1: CSV — refreshes cache, calls AI only for changed classes
      java -jar methodatlas.jar \
        -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
        -content-hash \
        -security-only \
        $CACHE_ARG \
        src/test/java \
        > .methodatlas-cache-new.csv
      mv .methodatlas-cache-new.csv .methodatlas-cache.csv

      # Pass 2: SARIF — reads exclusively from cache, zero AI calls
      java -jar methodatlas.jar \
        -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
        -sarif -security-only \
        -content-hash \
        -ai-cache .methodatlas-cache.csv \
        src/test/java \
        > methodatlas.sarif
    displayName: Run MethodAtlas with cache
    env:
      OPENAI_API_KEY: $(openaiApiKey)

  - task: PublishBuildArtifacts@1
    inputs:
      pathToPublish: methodatlas.sarif
      artifactName: methodatlas-sarif
    displayName: Publish SARIF artefact
```

On the first run (cache miss) every class is classified via the AI provider.
On subsequent runs only classes whose `content_hash` has changed incur an
API call; unchanged classes are read from the cache in milliseconds.

## Security test count gate

Fail the pipeline when the number of security-relevant test methods drops
compared to the baseline stored from the previous run on the default branch:

```yaml
stages:
  - stage: Build
    jobs:
      - job: BuildAndScan
        pool:
          vmImage: ubuntu-latest
        variables:
          - group: methodatlas-secrets
        steps:
          - task: JavaToolInstaller@0
            inputs:
              versionSpec: '21'
              jdkArchitectureOption: x64
              jdkSourceOption: PreInstalled
            displayName: Set up Java 21

          - script: |
              curl -fsSL -o methodatlas.jar \
                https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
            displayName: Download MethodAtlas

          - script: |
              java -jar methodatlas.jar \
                -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
                -security-only -content-hash \
                src/test/java > current.csv
            displayName: Run MethodAtlas — current scan
            env:
              OPENAI_API_KEY: $(openaiApiKey)

          - task: DownloadPipelineArtifact@2
            continueOnError: true
            inputs:
              artifactName: methodatlas-baseline
              targetPath: $(Build.SourcesDirectory)
            displayName: Download baseline (may not exist on first run)

          - script: |
              if [ ! -f baseline.csv ]; then
                echo "No baseline found — skipping count gate (first run)."
                cp current.csv baseline.csv
              else
                baseline=$(tail -n +2 baseline.csv | wc -l)
                current=$(tail -n +2 current.csv | wc -l)
                echo "Baseline security tests: $baseline"
                echo "Current security tests:  $current"
                if [ "$current" -lt "$baseline" ]; then
                  echo "##vso[task.logissue type=error]Security test count dropped from $baseline to $current"
                  exit 1
                fi
              fi
            displayName: Count gate

          - task: PublishBuildArtifacts@1
            inputs:
              pathToPublish: current.csv
              artifactName: methodatlas-baseline
            displayName: Update baseline
            condition: and(succeeded(), eq(variables['Build.SourceBranch'], 'refs/heads/main'))
```

The `##vso[task.logissue type=error]` prefix emits a formatted ADO pipeline
error annotation visible in the build summary.

## Full pipeline

The following `azure-pipelines.yml` combines caching, SARIF output, count
gate, and baseline management into a single runnable file. Adapt stage names
and `dependsOn` references to match your project's existing pipeline structure.

```yaml
trigger:
  - main

pr:
  - main

variables:
  - group: methodatlas-secrets
  - name: javaVersion
    value: '21'

stages:
  - stage: SecurityScan
    displayName: Security test scan
    jobs:
      - job: Scan
        displayName: MethodAtlas scan
        pool:
          vmImage: ubuntu-latest
        steps:
          - task: JavaToolInstaller@0
            inputs:
              versionSpec: $(javaVersion)
              jdkArchitectureOption: x64
              jdkSourceOption: PreInstalled
            displayName: Set up Java $(javaVersion)

          - script: |
              curl -fsSL -o methodatlas.jar \
                https://github.com/Accenture/MethodAtlas/releases/latest/download/methodatlas.jar
            displayName: Download MethodAtlas

          - task: Cache@2
            inputs:
              key: 'methodatlas | "$(Agent.OS)" | $(Build.SourcesDirectory)/src/test/java/**/*.java'
              restoreKeys: |
                methodatlas | "$(Agent.OS)"
              path: $(Build.SourcesDirectory)/.methodatlas-cache.csv
            displayName: Restore AI cache

          - script: |
              CACHE_ARG=""
              if [ -f .methodatlas-cache.csv ]; then
                CACHE_ARG="-ai-cache .methodatlas-cache.csv"
              fi

              # Pass 1: classify and update cache
              java -jar methodatlas.jar \
                -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
                -content-hash \
                -security-only \
                $CACHE_ARG \
                src/test/java \
                > .methodatlas-cache-new.csv
              mv .methodatlas-cache-new.csv .methodatlas-cache.csv

              # Pass 2: emit SARIF from cache (zero AI calls)
              java -jar methodatlas.jar \
                -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
                -sarif -security-only \
                -content-hash \
                -ai-cache .methodatlas-cache.csv \
                src/test/java \
                > methodatlas.sarif

              # Produce CSV for delta gating
              cp .methodatlas-cache.csv current.csv
            displayName: Run MethodAtlas
            env:
              OPENAI_API_KEY: $(openaiApiKey)

          - task: DownloadPipelineArtifact@2
            continueOnError: true
            inputs:
              artifactName: methodatlas-baseline
              targetPath: $(Build.SourcesDirectory)
            displayName: Download baseline

          - script: |
              if [ -f baseline.csv ]; then
                java -jar methodatlas.jar \
                  -diff baseline.csv current.csv > delta.txt
                cat delta.txt

                BLOCKED=0
                grep -qE "^- " delta.txt && BLOCKED=1
                grep -qE "security: true[[:space:]]*→[[:space:]]*false" delta.txt && BLOCKED=1

                if [ "$BLOCKED" -eq 1 ]; then
                  echo "##vso[task.logissue type=error]Security test regression detected — review delta.txt"
                  exit 1
                fi
              else
                echo "No baseline — skipping delta gate."
              fi
            displayName: Delta gate

          - task: PublishBuildArtifacts@1
            inputs:
              pathToPublish: methodatlas.sarif
              artifactName: methodatlas-sarif
            displayName: Publish SARIF

          - task: PublishBuildArtifacts@1
            inputs:
              pathToPublish: current.csv
              artifactName: methodatlas-baseline
            displayName: Update baseline
            condition: and(succeeded(), eq(variables['Build.SourceBranch'], 'refs/heads/main'))
```

## Using a YAML configuration file

For teams that prefer to keep MethodAtlas settings in version control, create
a `methodatlas.yml` at the project root. Reference it with `-config` in the
pipeline script:

```yaml
# methodatlas.yml — committed to source control
aiProvider: openai
aiModel: gpt-4o-mini
contentHash: true
securityOnly: true
```

```bash
java -jar methodatlas.jar -config methodatlas.yml -sarif src/test/java
```

The API key must be supplied at runtime via an environment variable. Do not
store secrets in the configuration file.

## Further reading

- [Microsoft — GitHub Advanced Security for Azure DevOps](https://learn.microsoft.com/en-us/azure/devops/repos/security/configure-github-advanced-security-features)
- [Microsoft — Cache@2 task](https://learn.microsoft.com/en-us/azure/devops/pipelines/tasks/reference/cache-v2)
- [Microsoft — ADO environment approvals and checks](https://learn.microsoft.com/en-us/azure/devops/pipelines/process/approvals)
- [MethodAtlas — AI Providers](../ai/providers.md)
- [MethodAtlas — Release Gating](release-gating.md)
- [MethodAtlas — Output Formats](../output-formats.md)
