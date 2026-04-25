# GitHub Actions

This page describes how to integrate MethodAtlas into a GitHub Actions
workflow. The techniques can be used individually or combined:

- **PR annotations** — inline findings on the pull request diff (no licence required)
- **SARIF upload** — findings in the GitHub Code Scanning tab (requires GitHub Advanced Security)
- **AI result caching** — skips re-classification of unchanged test classes
- **Security test count gate** — pipeline fails when security test coverage drops

## Prerequisites

| Requirement | Details |
|---|---|
| Java runtime | Java 21 or later; the examples use `actions/setup-java` with Eclipse Temurin |
| MethodAtlas | Downloaded at runtime from the GitHub release; no build step required |
| AI provider API key | Stored as a repository secret; not required for static inventory mode |
| GitHub Advanced Security | Required only for SARIF upload to Code Scanning; not required for annotations |

## Minimal workflow: PR annotations

The simplest integration uses the `-github-annotations` flag to emit
[GitHub workflow commands](https://docs.github.com/en/actions/writing-workflows/choosing-what-your-workflow-does/workflow-commands-for-github-actions)
that GitHub renders as inline annotations on the pull request diff:

- `::warning` when `ai_interaction_score >= 0.8` — the test only verifies
  that methods were called, not what they returned.
- `::notice` for all other security-relevant methods.

```yaml
name: Security test scan

on:
  pull_request:

jobs:
  scan:
    runs-on: ubuntu-latest
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

      - name: Scan security tests
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -github-annotations \
            src/test/java
```

!!! tip "No GitHub Advanced Security licence required"
    The `::notice` and `::warning` workflow commands are standard GitHub
    Actions features available on all plan tiers — Free, Team, and Enterprise
    — for both public and private repositories. SARIF upload via the
    `upload-sarif` action requires GitHub Advanced Security; annotation output
    does not.

## Caching AI results across runs

AI classification is the most expensive step in each scan. Use `-ai-cache`
together with `actions/cache` to skip re-classification of test classes whose
source has not changed since the last run:

```yaml
      - name: Restore MethodAtlas AI cache
        uses: actions/cache@v4
        with:
          path: .methodatlas-cache.csv
          key: methodatlas-${{ hashFiles('src/test/java/**/*.java') }}
          restore-keys: methodatlas-

      - name: Scan security tests with cache
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          CACHE_ARG=""
          if [ -f .methodatlas-cache.csv ]; then
            CACHE_ARG="-ai-cache .methodatlas-cache.csv"
          fi

          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -content-hash \
            -github-annotations \
            $CACHE_ARG \
            src/test/java \
            > .methodatlas-cache.csv
```

On the first run (cache miss) every class is classified via the AI provider.
On subsequent runs, only classes whose `content_hash` has changed since the
previous scan incur an API call; unchanged classes are read from the cache
in milliseconds.

## SARIF upload to Code Scanning

If your organisation has GitHub Advanced Security, upload the SARIF output
so findings appear in **Security → Code scanning**:

```yaml
      - name: Scan and produce SARIF
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -sarif \
            src/test/java \
            > methodatlas.sarif

      - name: Upload SARIF to Code Scanning
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: methodatlas.sarif
          category: security-tests
```

!!! note "GitHub Advanced Security"
    SARIF upload requires GitHub Advanced Security, which is included in
    GitHub Enterprise Cloud and GitHub Enterprise Server and is available as
    a paid add-on for private repositories on the Team and Free plans. For
    public repositories, Code Scanning is available at no additional cost.
    Use `-github-annotations` if Advanced Security is not available.

## Security test count gate

Fail the pipeline when the number of security-relevant test methods drops
compared to the `main` branch baseline. This protects against accidental or
silent removal of security tests.

```yaml
      - name: Save baseline
        if: github.ref == 'refs/heads/main'
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -security-only -content-hash \
            src/test/java > baseline.csv

      - uses: actions/upload-artifact@v4
        if: github.ref == 'refs/heads/main'
        with:
          name: methodatlas-baseline
          path: baseline.csv
          retention-days: 90

      - name: Download baseline
        if: github.event_name == 'pull_request'
        uses: actions/download-artifact@v4
        with:
          name: methodatlas-baseline
          path: .

      - name: Count gate
        if: github.event_name == 'pull_request'
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -security-only \
            src/test/java > current.csv

          baseline=$(tail -n +2 baseline.csv | wc -l)
          current=$(tail -n +2 current.csv | wc -l)

          echo "Baseline security tests: $baseline"
          echo "Current security tests:  $current"

          if [ "$current" -lt "$baseline" ]; then
            echo "::error::Security test count dropped from $baseline to $current"
            exit 1
          fi
```

## Full workflow

The following workflow combines all four techniques — caching, annotations,
SARIF upload, and count gate — into a single reusable definition. The
annotation and SARIF steps run in sequence so that each produces a clean,
single-format output stream.

```yaml
name: Security test scan

on:
  push:
    branches: [main]
  pull_request:

jobs:
  scan:
    runs-on: ubuntu-latest
    permissions:
      security-events: write   # required for upload-sarif
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

      - name: Restore AI cache
        uses: actions/cache@v4
        with:
          path: .methodatlas-cache.csv
          key: methodatlas-${{ hashFiles('src/test/java/**/*.java') }}
          restore-keys: methodatlas-

      - name: Run MethodAtlas — annotations
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          CACHE_ARG=""
          if [ -f .methodatlas-cache.csv ]; then
            CACHE_ARG="-ai-cache .methodatlas-cache.csv"
          fi

          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -content-hash \
            -github-annotations \
            $CACHE_ARG \
            src/test/java \
            > .methodatlas-cache.csv

      - name: Run MethodAtlas — SARIF
        if: github.ref == 'refs/heads/main'
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -sarif \
            -ai-cache .methodatlas-cache.csv \
            src/test/java \
            > methodatlas.sarif

      - name: Upload SARIF
        if: github.ref == 'refs/heads/main'
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: methodatlas.sarif
          category: security-tests
```

## Persisting human corrections with an override file

AI classification is non-deterministic: the same test method may receive
different tags or a different security-relevance verdict across runs. An
[override file](../ai/overrides.md) locks in human-reviewed decisions so they
survive model changes, prompt updates, and re-runs.

Store the file in version control (conventionally `.methodatlas-overrides.yaml`
at the repository root). Pass it on every scan with `-override-file`:

```yaml
      - name: Run MethodAtlas with overrides
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          OVERRIDE_ARGS=()
          if [ -f .methodatlas-overrides.yaml ]; then
            OVERRIDE_ARGS=("-override-file" ".methodatlas-overrides.yaml")
          fi

          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -sarif \
            "${OVERRIDE_ARGS[@]}" \
            src/test/java \
            > methodatlas.sarif
```

The file check (`[ -f ... ]`) lets you commit the workflow before the override
file exists. Once the file is committed, subsequent runs pick it up
automatically. Pull request diffs on the override file serve as the audit trail
for each human classification decision.

See [Classification Overrides](../ai/overrides.md) for the full file format
reference.

## Using GitHub Models as the AI provider

[GitHub Models](https://github.com/marketplace/models) provides free inference
for supported models using the `GITHUB_TOKEN` automatically available in every
GitHub Actions run. No additional secrets or billing setup is required.

```yaml
      - name: Run MethodAtlas with GitHub Models
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider github_models \
            -ai-model gpt-4o-mini \
            -ai-api-key-env GITHUB_TOKEN \
            -github-annotations \
            src/test/java
```

See [AI Providers — GitHub Models](../ai/providers.md) for the list of
available models and rate limits.
