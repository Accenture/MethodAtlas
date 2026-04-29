# GitHub Actions

This page describes how to integrate MethodAtlas into a GitHub Actions
workflow. The techniques can be used individually or combined:

- **PR annotations** — inline findings on the pull request diff (no licence required)
- **SARIF upload** — findings in the GitHub Code Scanning tab (requires GitHub Advanced Security)
- **AI result caching** — skips re-classification of unchanged test classes
- **Security test count gate** — pipeline fails when security test coverage drops

## Prerequisites

| Requirement              | Details |
|--------------------------|---------|
| Java runtime             | Java 21 or later; the examples use `actions/setup-java` with Eclipse Temurin |
| MethodAtlas              | Downloaded at runtime from the GitHub release; no build step required |
| AI provider API key      | Stored as a repository secret; not required for static inventory mode |
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
            -content-hash \
            -github-annotations \
            src/test/java
```

See [AI Providers — GitHub Models](../ai/providers.md) for the list of
available models and rate limits.

## Caching AI results across runs

AI classification is the most expensive step in each scan. Use
[`-content-hash`](../cli-reference.md#-content-hash) together with
`actions/cache` to skip re-classification of test classes whose source has
not changed since the last run.

MethodAtlas computes a SHA-256 content fingerprint (`content_hash`) for each
test class and stores it alongside the AI classification in CSV output. On the
next run, classes whose hash matches a cache entry are served locally — no API
call is made. Only changed or new classes incur a provider call.

The cache file is stored compressed (`.gz`) to minimise GitHub Actions cache
storage consumption. CSV text typically compresses to 10–20 % of its original
size, making the cache practical even for very large test suites.

### Caching with CSV / annotation output

```yaml
      - name: Restore MethodAtlas AI cache
        uses: actions/cache@v4
        with:
          path: .methodatlas-cache.csv.gz
          key: methodatlas-ai-${{ github.ref_name }}-${{ github.sha }}
          restore-keys: |
            methodatlas-ai-${{ github.ref_name }}-
            methodatlas-ai-

      - name: Scan security tests with cache
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          [ -f .methodatlas-cache.csv.gz ] && gunzip -k .methodatlas-cache.csv.gz
          CACHE_ARGS=()
          [ -f .methodatlas-cache.csv ] && CACHE_ARGS=("-ai-cache" ".methodatlas-cache.csv")

          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -content-hash \
            -github-annotations \
            "${CACHE_ARGS[@]}" \
            src/test/java \
            > .methodatlas-cache-new.csv
          mv .methodatlas-cache-new.csv .methodatlas-cache.csv
          gzip -c .methodatlas-cache.csv > .methodatlas-cache.csv.gz
```

On the first run (cold cache) every class is classified via the AI provider.
On subsequent runs, only classes whose `content_hash` has changed since the
previous scan incur an API call; unchanged classes are read from the cache
in milliseconds.

### Caching with SARIF output — two-pass approach

SARIF output does not carry the `content_hash` column required by the cache
loader, so a SARIF file cannot itself serve as the next run's cache. Use two
consecutive invocations:

1. **CSV pass** — refreshes the cache; calls AI only for changed or new classes.
2. **SARIF pass** — reads exclusively from the cache produced in pass 1; makes
   zero AI calls.

```yaml
      - name: Restore MethodAtlas AI cache
        uses: actions/cache/restore@v4
        with:
          path: .methodatlas-cache.csv.gz
          key: methodatlas-ai-${{ github.ref_name }}-${{ github.sha }}
          restore-keys: |
            methodatlas-ai-${{ github.ref_name }}-
            methodatlas-ai-

      - name: Run MethodAtlas — CSV pass (cache refresh)
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          [ -f .methodatlas-cache.csv.gz ] && gunzip -k .methodatlas-cache.csv.gz
          CACHE_ARGS=()
          [ -f .methodatlas-cache.csv ] && CACHE_ARGS=("-ai-cache" ".methodatlas-cache.csv")
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -content-hash \
            "${CACHE_ARGS[@]}" \
            src/test/java \
            > .methodatlas-cache-new.csv
          mv .methodatlas-cache-new.csv .methodatlas-cache.csv
          # Keep the uncompressed file for pass 2; produce .gz for storage.
          gzip -c .methodatlas-cache.csv > .methodatlas-cache.csv.gz

      - name: Run MethodAtlas — SARIF pass (zero AI calls)
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider openai -ai-api-key-env OPENAI_API_KEY \
            -content-hash \
            -ai-cache .methodatlas-cache.csv \
            -sarif \
            src/test/java \
            > methodatlas.sarif

      - name: Save MethodAtlas AI cache
        if: success()
        uses: actions/cache/save@v4
        with:
          path: .methodatlas-cache.csv.gz
          key: methodatlas-ai-${{ github.ref_name }}-${{ github.sha }}
```

!!! tip "Why two passes?"
    The SARIF pass reads all classifications from the local cache, so it makes
    no AI calls and is not subject to rate limits. This also guarantees that
    findings from unchanged classes remain present in the SARIF output — if
    they were omitted, GitHub Code Scanning would close the corresponding alerts
    as resolved between runs.

See [AI Result Caching](../ai/caching.md) for a detailed explanation of how the
cache works and how to combine it with the `-diff` command.

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
            -content-hash \
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

### Example finding in Code Scanning

After upload, each finding appears as a Code Scanning alert with the
suggested annotations, a remediation command, and a link to the affected
test method:

<figure>
  <a href="../../images/github-code-scanning-finding.png" target="_blank">
    <img src="../../images/github-code-scanning-finding.png"
         alt="GitHub Code Scanning alert for a security/access-control finding generated by MethodAtlas"
         style="max-width: 100%; border: 1px solid #e1e4e8; border-radius: 6px; cursor: zoom-in;">
  </a>
  <figcaption>
    Code Scanning alert showing the suggested <code>@DisplayName</code> and
    <code>@Tag</code> values and the <code>./methodatlas -ai -apply-tags</code>
    remediation command — <em>click to view full size</em>
  </figcaption>
</figure>

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
SARIF upload, and count gate — into a single, runnable definition.

The cache is refreshed in a CSV pass that calls the AI only for changed or new
classes. The annotation and SARIF passes both read from that cache and make
zero AI calls, eliminating redundant provider traffic and rate-limit exposure.

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

      - name: Restore AI classification cache
        uses: actions/cache/restore@v4
        with:
          path: .methodatlas-cache.csv.gz
          key: methodatlas-ai-${{ github.ref_name }}-${{ github.sha }}
          restore-keys: |
            methodatlas-ai-${{ github.ref_name }}-
            methodatlas-ai-

      # ── Pass 1: CSV ────────────────────────────────────────────────────────
      # Refreshes the cache.  Calls the AI only for classes that changed since
      # the last run; all other classes are served from the restored cache.
      - name: Run MethodAtlas — CSV pass (cache refresh + annotations)
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          [ -f .methodatlas-cache.csv.gz ] && gunzip -k .methodatlas-cache.csv.gz
          CACHE_ARGS=()
          [ -f .methodatlas-cache.csv ] && CACHE_ARGS=("-ai-cache" ".methodatlas-cache.csv")

          java -jar methodatlas.jar \
            -ai -ai-provider github_models \
            -ai-model gpt-4o-mini \
            -ai-api-key-env GITHUB_TOKEN \
            -content-hash \
            -github-annotations \
            "${CACHE_ARGS[@]}" \
            src/test/java \
            > .methodatlas-cache-new.csv
          mv .methodatlas-cache-new.csv .methodatlas-cache.csv
          gzip -c .methodatlas-cache.csv > .methodatlas-cache.csv.gz

      # ── Pass 2: SARIF ──────────────────────────────────────────────────────
      # Reads exclusively from the cache produced in pass 1; zero AI calls.
      # Runs only on pushes to main (where Code Scanning upload is meaningful).
      - name: Run MethodAtlas — SARIF pass (zero AI calls)
        if: github.ref == 'refs/heads/main'
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          java -jar methodatlas.jar \
            -ai -ai-provider github_models \
            -ai-model gpt-4o-mini \
            -ai-api-key-env GITHUB_TOKEN \
            -content-hash \
            -ai-cache .methodatlas-cache.csv \
            -sarif \
            src/test/java \
            > methodatlas.sarif

      - name: Save AI classification cache
        if: success()
        uses: actions/cache/save@v4
        with:
          path: .methodatlas-cache.csv.gz
          key: methodatlas-ai-${{ github.ref_name }}-${{ github.sha }}

      - name: Upload SARIF
        if: github.ref == 'refs/heads/main'
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: methodatlas.sarif
          category: security-tests

      - name: Upload SARIF as workflow artifact
        if: github.ref == 'refs/heads/main'
        uses: actions/upload-artifact@v4
        with:
          name: methodatlas-sarif
          path: methodatlas.sarif
          retention-days: 30
```

!!! tip "Switching to a different AI provider"
    The full workflow above uses `github_models` (no extra secrets required).
    To use OpenAI, replace `-ai-provider github_models -ai-model gpt-4o-mini
    -ai-api-key-env GITHUB_TOKEN` with
    `-ai-provider openai -ai-api-key-env OPENAI_API_KEY` and store
    `OPENAI_API_KEY` as a repository secret. All other steps are identical.

## Persisting human corrections with an override file

AI classification is non-deterministic: the same test method may receive
different tags or a different security-relevance verdict across runs. An
[override file](../ai/overrides.md) locks in human-reviewed decisions so they
survive model changes, prompt updates, and re-runs.

Store the file in version control (conventionally `.methodatlas-overrides.yaml`
at the repository root). Pass it on every scan with
[`-override-file`](../cli-reference.md#-override-file):

```yaml
      - name: Run MethodAtlas with overrides
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          OVERRIDE_ARGS=()
          if [ -f .methodatlas-overrides.yaml ]; then
            OVERRIDE_ARGS=("-override-file" ".methodatlas-overrides.yaml")
          fi

          java -jar methodatlas.jar \
            -ai -ai-provider github_models \
            -ai-model gpt-4o-mini \
            -ai-api-key-env GITHUB_TOKEN \
            -content-hash \
            -sarif \
            "${OVERRIDE_ARGS[@]}" \
            src/test/java \
            > methodatlas.sarif
```

The file check (`[ -f ... ]`) lets you commit the workflow before the override
file exists. Once the file is committed, subsequent runs pick it up
automatically. Pull request diffs on the override file serve as the audit trail
for each human classification decision.

See [Classification Overrides](../ai/overrides.md) for the file format
reference and [Remote Override Sources](../ai/remote-overrides.md) for a
strategy comparison that covers security-team repositories, HTTPS artifact
servers, and reusable workflows.
